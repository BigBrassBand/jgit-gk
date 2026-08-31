/*
 * Copyright (C) 2013 Christian Halstrick <christian.halstrick@sap.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.bigbrassband.common.apache;

import com.bigbrassband.common.apache.internal.HttpApacheText;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.TemporaryBuffer.LocalFile;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.eclipse.jgit.util.HttpSupport.METHOD_GET;
import static org.eclipse.jgit.util.HttpSupport.METHOD_HEAD;
import static org.eclipse.jgit.util.HttpSupport.METHOD_POST;
import static org.eclipse.jgit.util.HttpSupport.METHOD_PUT;

/**
 * A {@link HttpConnection} which uses {@link HttpClient}.
 * <p>
 * The JGit interface implemented here declares neither {@code close} nor {@code disconnect}, so
 * no caller can end an exchange and releasing the socket is this class's own business. That is
 * what most of the file is, spread over five nested classes:
 * <ul>
 * <li>{@code Exchange} holds the state of one exchange and renders the single verdict the
 * counters are told about. It is deliberately reachable from neither the connection nor the body
 * stream.
 * <li>{@code ResponseBodyDrain} is a response interceptor registered first, so it sees bytes as
 * they came off the wire rather than decompressed. It reads a body that fits the limit and gets
 * the socket back before the caller sees the response at all. It runs only when a finite read
 * timeout is set, because nothing else would bound how long it takes.
 * <li>{@code BufferedBody} and {@code FailedBody} are the replacement entities it installs, for
 * a body that arrived whole and for one that broke in mid-flight.
 * <li>{@code TrackedBodyStream} wraps a body handed to the caller and tells the exchange when
 * that body reaches its end or is closed.
 * </ul>
 * <p>
 * <b>Three places can render the verdict, and one would not do.</b> {@code TransportHttp} drops
 * the connection object as soon as it holds the body stream, so on every fetch bigger than the
 * limit this object becomes unreachable while its body is still being read. A single cleanup
 * action on the connection would therefore count each real pack fetch as abandoned. Instead the
 * exchange is settled by whichever comes first: the exec chain returning, for a body already
 * buffered; the body stream reaching its end or being closed; or collection — of the connection,
 * or of the stream, two cleaners with the connection's deferring to the stream's while a body is
 * out. A compare-and-set inside {@code Exchange} keeps that to one verdict per connection, so a
 * redirect chain that released four sockets still reports once.
 * <p>
 * <b>No cleanup action may reach what it watches.</b> An action able to reach this connection
 * would keep it alive for good, turning the socket leak into a memory leak, so every registered
 * action is a bound method reference on {@code Exchange} — which holds nothing but the
 * {@link Closeable} response. The same constraint read the other way is why
 * {@link Reference#reachabilityFence} appears in {@link #getInputStream()} and in
 * {@code TrackedBodyStream}: without it a cleaner may run while the method it belongs to is
 * still deciding, and close the socket under a stream about to be returned or still being read.
 *
 * @since 3.3
 */
public class B3HttpClientConnection implements HttpConnection {
	private static final Cleaner CLEANER = Cleaner.create();
	//the socket timeout bounds one read, not the whole drain: read() returns as soon as any
	//byte arrives, so a server sending one at a time is bounded only by the limit in bytes.
	private static final int DRAIN_BUDGET_READ_TIMEOUTS = 5;
	private static final AtomicLong initCount = new AtomicLong(0);
	private static final AtomicLong closeCount = new AtomicLong(0);
	private static final AtomicLong abandonedCount = new AtomicLong(0);
	private static final AtomicLong streamedUnreadCount = new AtomicLong(0);
	private static final AtomicLong streamAbandonedCount = new AtomicLong(0);

	private final B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
	private final int maxBufferedResponseBytes;
	private final Exchange exchange = new Exchange();
	//CloseableHttpClient rather than HttpClient because only its execute() returns a
	//CloseableHttpResponse, and the response is what has to be closed. The client itself is
	//never closed on purpose: the connection manager can come from an outside factory, and
	//HttpClientBuilder adds that manager's shutdown to the client's own closeable resources
	//unless it is marked shared — so closing the client here would stop a manager that other
	//connections are still using. Nothing leaks by leaving it open: NoConnectionReuseStrategy
	//means no socket is ever parked in the manager to begin with.
	private CloseableHttpClient client;

	private URL url;

	private HttpUriRequest req;

	private CloseableHttpResponse resp = null;

	private String method = "GET"; //$NON-NLS-1$

	private TemporaryBufferEntity entity;

	private boolean isUsingProxy = false;

	private Proxy proxy;

	private Integer connectTimeoutMilliseconds;

	private boolean ignoreConnectTimeoutMillisecondsSets=false;

	private Integer readTimeoutMilliseconds;

	private Boolean followRedirects;

	private X509HostnameVerifier hostnameverifier;

	private SSLContext ctx;

	private CredentialsProvider credentialsProvider=null;

	/**
	 *
	 * @param credentialsProvider credentialsProvider
	 */
	public void setCredentialsProvider(CredentialsProvider credentialsProvider) {
		this.credentialsProvider = credentialsProvider;
	}

	/**
	 * @return number of connections created since last call (resets counter)
	 */
	public static long getInitCount() {
		return initCount.getAndSet(0L);
	}

	/**
	 * @return number of exchanges that released their connection since last call (resets counter)
	 */
	public static long getCloseCount() {
		return closeCount.getAndSet(0L);
	}

	/**
	 * @return number of exchanges that ended without their connection being released, since last
	 *         call (resets counter). Every such exchange also increments exactly one of
	 *         {@link #getStreamedUnreadCount()} and {@link #getStreamAbandonedCount()}, so the
	 *         underlying totals agree over time — but three separate reads are not one snapshot, and
	 *         a cleanup landing between them moves an increment into the next window.
	 *         <p>
	 *         Nor does it pair off exactly against {@link #getInitCount()}. Three kinds of
	 *         connection count as an init and then report nothing: one whose URL fails to parse,
	 *         one simply constructed and dropped, and one built around a caller-supplied
	 *         {@code CloseableHttpClient}, which carries none of the interceptor this bookkeeping
	 *         rides on and so goes unreported even though it did issue a request. A small residual
	 *         is expected and is not a leak.
	 */
	public static long getAbandonedCount() {
		return abandonedCount.getAndSet(0L);
	}

	/**
	 * @return number of responses whose body was too big to buffer and was then never read at all,
	 *         since last call (resets counter).
	 *         <p>
	 *         Do not read this as "the limit is too low" on its own — that is only one of its
	 *         causes. A caller is also entitled to walk away from a big body it never wanted:
	 *         jgit's {@code TransportHttp} throws on any non-200 without reading a byte, so a
	 *         large error page lands here having cost nothing. Raise the limit only once the
	 *         bodies behind the count are known to be ones somebody meant to read.
	 */
	public static long getStreamedUnreadCount() {
		return streamedUnreadCount.getAndSet(0L);
	}

	/**
	 * @return number of response streams that were handed to a caller and dropped before their end,
	 *         since last call (resets counter). Independent of the buffering limit: the caller took
	 *         the body and neither finished nor closed it.
	 */
	public static long getStreamAbandonedCount() {
		return streamAbandonedCount.getAndSet(0L);
	}

	private CloseableHttpClient getClient() {
		if (client == null) {
			HttpClientBuilder clientBuilder = HttpClients.custom();
			clientBuilder.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE);
			//first, so it runs ahead of ResponseContentEncoding and the limit then counts bytes off
			//the wire rather than decompressed bytes
			clientBuilder.addInterceptorFirst(new ResponseBodyDrain(exchange, maxBufferedResponseBytes,
					drainBudgetMillis()));
			if(httpClientConnectionManagerFactory!=null)
				clientBuilder.setConnectionManager(httpClientConnectionManagerFactory.getConnectionManager());
			RequestConfig.Builder configBuilder = RequestConfig.custom();
			if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
				isUsingProxy = true;
				InetSocketAddress adr = (InetSocketAddress) proxy.address();
				clientBuilder.setProxy(
						new HttpHost(adr.getHostName(), adr.getPort()));
			}
			if (readTimeoutMilliseconds != null) {
				configBuilder.setSocketTimeout(readTimeoutMilliseconds);
			}
			if (connectTimeoutMilliseconds != null) {
				configBuilder.setConnectTimeout(connectTimeoutMilliseconds);
			}
			if (followRedirects != null) {
				configBuilder
						.setRedirectsEnabled(followRedirects);
			}
			if (hostnameverifier != null) {
				SSLConnectionSocketFactory sslConnectionFactory = new SSLConnectionSocketFactory(
						getSSLContext(), hostnameverifier);
				clientBuilder.setSSLSocketFactory(sslConnectionFactory);
				Registry<ConnectionSocketFactory> registry = RegistryBuilder
						.<ConnectionSocketFactory> create()
						.register("https", sslConnectionFactory)
						.register("http", PlainConnectionSocketFactory.getSocketFactory())
						.build();
				clientBuilder.setConnectionManager(
						new BasicHttpClientConnectionManager(registry));
			}
			configBuilder.setCookieSpec(CookieSpecs.STANDARD);
			clientBuilder.setDefaultRequestConfig(configBuilder.build());

			client = clientBuilder.build();
		}

		return client;
	}

	//zero means the drain does not run. A read timeout of zero reaches setSocketTimeout as
	//"wait forever", and jgit passes exactly that when the repository configures no timeout
	//(RemoteConfig reads KEY_TIMEOUT with a default of 0), so a single read need never return.
	private long drainBudgetMillis() {
		if (readTimeoutMilliseconds == null || readTimeoutMilliseconds <= 0) {
			return 0L;
		}
		return (long) readTimeoutMilliseconds * DRAIN_BUDGET_READ_TIMEOUTS;
	}

	private SSLContext getSSLContext() {
		if (ctx == null) {
			try {
				ctx = SSLContext.getInstance("TLS"); //$NON-NLS-1$
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(
						HttpApacheText.get().unexpectedSSLContextException, e);
			}
		}
		return ctx;
	}

	/**
	 * Sets the buffer from which to take the request body
	 *
	 * @param buffer buffer
	 */
	public void setBuffer(TemporaryBuffer buffer) {
		this.entity = new TemporaryBufferEntity(buffer);
	}

	/**
	 *
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @param maxBufferedResponseBytes maxBufferedResponseBytes
	 * @throws MalformedURLException MalformedURLException
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory, int maxBufferedResponseBytes) throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, null, httpClientConnectionManagerFactory, maxBufferedResponseBytes);
	}

	/**
	 *
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @param maxBufferedResponseBytes maxBufferedResponseBytes
	 * @throws MalformedURLException MalformedURLException
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory, int maxBufferedResponseBytes)
			throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, proxy, null, httpClientConnectionManagerFactory, maxBufferedResponseBytes);
	}

	/**
	 *
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param cl cl
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @param maxBufferedResponseBytes largest response body read into memory so that the connection
	 *            can be released before the caller sees the response; bigger bodies keep streaming.
	 *            Must be positive. Ignored when {@code cl} is supplied, since the buffering rides on
	 *            an interceptor of the client this class builds itself.
	 * @throws MalformedURLException MalformedURLException
	 * @throws IllegalArgumentException if maxBufferedResponseBytes is not positive
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, CloseableHttpClient cl, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory, int maxBufferedResponseBytes)
			throws MalformedURLException {
		if (maxBufferedResponseBytes <= 0) {
			throw new IllegalArgumentException("maxBufferedResponseBytes must be positive: " //$NON-NLS-1$
					+ maxBufferedResponseBytes);
		}
		initCount.incrementAndGet();
		Exchange onCollection = this.exchange;
		//a cleanup action able to reach this connection would keep it alive for good, turning the
		//socket leak into a memory leak — hence the exchange, and never this, as the receiver
		CLEANER.register(this, onCollection::connectionGone);
		this.client = cl;
		this.url = new URL(urlStr);
		this.proxy = proxy;
		this.httpClientConnectionManagerFactory=httpClientConnectionManagerFactory;
		this.maxBufferedResponseBytes=maxBufferedResponseBytes;

		//if the caller passed in connect timeout seconds --- then don't let
		//jgit come along and change it later
		if(connectTimeoutSeconds>0)
		{
			this.connectTimeoutMilliseconds=connectTimeoutSeconds*1000;
			ignoreConnectTimeoutMillisecondsSets=true;
		}
	}

	@Override
	public int getResponseCode() throws IOException {
		execute();
		return resp.getStatusLine().getStatusCode();
	}

	@Override
	public URL getURL() {
		return url;
	}

	@Override
	public String getResponseMessage() throws IOException {
		execute();
		return resp.getStatusLine().getReasonPhrase();
	}

	private void execute() throws IOException, ClientProtocolException {
		if (resp != null) {
			return;
		}

		HttpClientContext context = HttpClientContext.create();
		if (credentialsProvider != null) {
			context.setCredentialsProvider(credentialsProvider);
		}
		if (proxy != null && !Proxy.NO_PROXY.equals(proxy)) {
			B3ProxyCredentialsUtil.setProxyIfNeeded(context, req.getURI() != null ? req.getURI().getScheme() : null);
		}

		try {
			if (entity != null && req instanceof HttpEntityEnclosingRequest) {
				HttpEntityEnclosingRequest eReq = (HttpEntityEnclosingRequest) req;
				eReq.setEntity(entity);
			}
			resp = getClient().execute(req,context);
		} finally {
			//a null resp is the only sign of a failed chain available here, and the exchange has to
			//be told either way: an over-budget body drained just before the throw would otherwise
			//be left looking like a body still on the socket, i.e. reported as abandoned
			if (resp == null) {
				exchange.exchangeFailed();
			} else {
				exchange.exchangeSettled();
			}
			//after the bookkeeping: this close is allowed to throw, and doing it first would carry
			//the throw straight past it
			if (entity != null) {
				entity.close();
				entity = null;
			}
		}
	}

	@Override
	public Map<String, List<String>> getHeaderFields() {
		Map<String, List<String>> ret = new HashMap<>();
		for (Header hdr : resp.getAllHeaders()) {
			List<String> list = ret.get(hdr.getName());
			if (list == null) {
				list = new LinkedList<>();
				ret.put(hdr.getName(), list);
			}
			list.add(hdr.getValue());
		}
		return ret;
	}

	@Override
	public void setRequestProperty(String name, String value) {
		req.addHeader(name, value);
	}

	@Override
	public void setRequestMethod(String method) throws ProtocolException {
		this.method = method;
		if (METHOD_GET.equalsIgnoreCase(method)) {
			req = new HttpGet(url.toString());
		} else if (METHOD_HEAD.equalsIgnoreCase(method)) {
			req = new HttpHead(url.toString());
		} else if (METHOD_PUT.equalsIgnoreCase(method)) {
			req = new HttpPut(url.toString());
		} else if (METHOD_POST.equalsIgnoreCase(method)) {
			req = new HttpPost(url.toString());
		} else {
			this.method = null;
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public void setUseCaches(boolean usecaches) {
		// not needed
	}

	@Override
	public void setConnectTimeout(int timeout) {
		if(ignoreConnectTimeoutMillisecondsSets)
			return;
		this.connectTimeoutMilliseconds = timeout;
	}

	@Override
	public void setReadTimeout(int readTimeout) {
		this.readTimeoutMilliseconds = readTimeout;
	}

	@Override
	public String getContentType() {
		HttpEntity responseEntity = resp.getEntity();
		if (responseEntity != null) {
			Header contentType = responseEntity.getContentType();
			if (contentType != null)
				return contentType.getValue();
		}
		return null;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		try {
			if (exchange.isBodyOnSocket()) {
				//JGit drops the connection as soon as it has the stream (TransportHttp does
				//in.add(openInputStream(conn)); conn = null), so from here on the body's fate is
				//decided by the stream's own lifetime, not by ours
				TrackedBodyStream body = new TrackedBodyStream(resp.getEntity().getContent(), exchange);
				Exchange onCollection = this.exchange;
				CLEANER.register(body, onCollection::bodyStreamGone);
				//only once something is watching that stream: a taken body nobody can settle would
				//leave the connection's cleaner deferring for good, socket included
				exchange.bodyTaken();
				return body;
			}
			return resp.getEntity().getContent();
		} finally {
			//our own cleaner must not run while this method decides, or it would close the socket
			//under the stream being returned and the caller would read a truncated body as if whole
			Reference.reachabilityFence(this);
		}
	}

	// will return only the first field
	@Override
	public String getHeaderField(@NonNull String name) {
		Header header = resp.getFirstHeader(name);
		return (header == null) ? null : header.getValue();
	}

	@Override
	public List<String> getHeaderFields(@NonNull String name) {
		Header[] headers = resp.getHeaders(name);
		List<String> fields = new ArrayList<>();
		if (headers != null) {
			for (Header header : headers) {
				fields.add(header.getValue());
			}
		}
		return fields;
	}

	@Override
	public int getContentLength() {
		Header contentLength = resp.getFirstHeader(HttpHeaders.CONTENT_LENGTH); //$NON-NLS-1$
		if (contentLength == null) {
			return -1;
		}

		try {
			int l = Integer.parseInt(contentLength.getValue());
			return l < 0 ? -1 : l;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	public void setInstanceFollowRedirects(boolean followRedirects) {
		this.followRedirects = followRedirects;
	}

	@Override
	public void setDoOutput(boolean dooutput) {
		// TODO: check whether we can really ignore this.
	}

	@Override
	public void setFixedLengthStreamingMode(int contentLength) {
		if (entity != null)
			throw new IllegalArgumentException();
		entity = new TemporaryBufferEntity(new LocalFile(null));
		entity.setContentLength(contentLength);
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		if (entity == null)
			entity = new TemporaryBufferEntity(new LocalFile(null));
		return entity.getBuffer();
	}

	@Override
	public void setChunkedStreamingMode(int chunklen) {
		if (entity == null)
			entity = new TemporaryBufferEntity(new LocalFile(null));
		entity.setChunked(true);
	}

	@Override
	public String getRequestMethod() {
		return method;
	}

	@Override
	public boolean usingProxy() {
		return isUsingProxy;
	}

	@Override
	public void connect() throws IOException {
		execute();
	}

	@Override
	public void setHostnameVerifier(final HostnameVerifier hostnameverifier) {
		this.hostnameverifier = new X509HostnameVerifier() {
			@Override
			public boolean verify(String hostname, SSLSession session) {
				return hostnameverifier.verify(hostname, session);
			}

			@Override
			public void verify(String host, String[] cns, String[] subjectAlts)
					throws SSLException {
				throw new UnsupportedOperationException(); // TODO message
			}

			@Override
			public void verify(String host, X509Certificate cert)
					throws SSLException {
				throw new UnsupportedOperationException(); // TODO message
			}

			@Override
			public void verify(String host, SSLSocket ssl) throws IOException {
				hostnameverifier.verify(host, ssl.getSession());
			}
		};
	}

	@Override
	public void configure(KeyManager[] km, TrustManager[] tm,
			SecureRandom random) throws KeyManagementException {
		getSSLContext().init(km, tm, random);
	}

	private static void closeQuietly(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException e) {
				//the exchange is over either way, and there is no caller left to report to
			}
		}
	}

	/**
	 * State of one HTTP exchange, shared between the connection and a body stream that outlives it.
	 * <p>
	 * Reachable from neither: a cleanup action that could reach the object it watches would keep that
	 * object alive for good, so the socket leak would become a memory leak. Two objects can end the
	 * exchange and either may be collected first, so {@link #account()} guards the verdict with a
	 * compare-and-set — one connection contributes one verdict even when a caller thread and a
	 * cleaner thread arrive together.
	 */
	private static final class Exchange {
		private enum State {
			NOT_STARTED, RELEASED, BODY_ON_SOCKET, BODY_TAKEN
		}

		private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);

		private final AtomicBoolean accounted = new AtomicBoolean(false);

		private volatile Closeable response;

		void bodyOnSocket(Closeable resp) {
			this.response = resp;
			state.set(State.BODY_ON_SOCKET);
		}

		void bodyReleased(Closeable resp) {
			this.response = resp;
			state.set(State.RELEASED);
		}

		boolean isBodyOnSocket() {
			State current = state.get();
			return current == State.BODY_ON_SOCKET || current == State.BODY_TAKEN;
		}

		void bodyTaken() {
			state.compareAndSet(State.BODY_ON_SOCKET, State.BODY_TAKEN);
		}

		//the caller reached the end of a taken body or closed it, which ends the exchange for good:
		//no further hop can follow, so the verdict need not wait for collection
		void bodyFinished() {
			//the stream underneath releases the connection on its own — except under
			//decompression, where GZIPInputStream reports its own end after its trailer and only
			//consults the stream below it while available() > 0, which on a socket it is not. So
			//the end of a decompressed body would otherwise record a release nobody performed.
			closeQuietly(response);
			state.set(State.RELEASED);
			account();
		}

		//the exec chain has returned, so no further hop can revise the verdict; a body already
		//buffered and released is counted here rather than waiting to be collected
		void exchangeSettled() {
			if (state.get() == State.RELEASED) {
				account();
			}
		}

		//the exec chain threw with a body left on the socket. It closes the response on the way out
		//of every exception it declares, and closing an already-released holder is a no-op, so this
		//can say released rather than let a cleaner report a leak that did not happen. Every other
		//state already tells the truth and is left to be counted at collection.
		void exchangeFailed() {
			if (state.get() != State.BODY_ON_SOCKET) {
				return;
			}
			closeQuietly(response);
			state.set(State.RELEASED);
			account();
		}

		void connectionGone() {
			if (state.get() == State.BODY_TAKEN) {
				//the stream is still out there and settles this itself, one way or the other
				return;
			}
			account();
		}

		void bodyStreamGone() {
			account();
		}

		//one verdict per connection, so that init roughly equals close + abandoned for a metrics
		//reader; a redirect chain releases several sockets under one connection and must not report
		//a release for each.
		//Guarding the closing with that same compare-and-set would mean that after the first
		//verdict no socket could ever be closed again — and a second exchange on this
		//connection still has one to give back. Closing an already-released holder is a
		//no-op, so doing it every time costs nothing.
		private void account() {
			State current = state.get();
			if (current == State.BODY_ON_SOCKET || current == State.BODY_TAKEN) {
				closeQuietly(response);
			}
			if (!accounted.compareAndSet(false, true)) {
				return;
			}
			switch (current) {
			case RELEASED:
				closeCount.incrementAndGet();
				break;
			case BODY_ON_SOCKET:
				abandonedCount.incrementAndGet();
				streamedUnreadCount.incrementAndGet();
				break;
			case BODY_TAKEN:
				abandonedCount.incrementAndGet();
				streamAbandonedCount.incrementAndGet();
				break;
			default:
				//NOT_STARTED: no request was ever issued, so there is nothing to report
				break;
			}
		}
	}

	/**
	 * Reads a response body small enough to buffer and releases the connection before the caller ever
	 * sees the response. Bigger bodies are put back together from the bytes already read plus the
	 * rest of the socket, and keep releasing the way they do without this interceptor: when the caller
	 * reaches the end of the stream or closes it.
	 * <p>
	 * Runs once per hop, so a redirect chain resolved inside httpclient reaches it for every response
	 * in the chain and the exchange state moves back and forth accordingly.
	 * <p>
	 * Does not run at all without a finite read timeout to derive a budget from, since reading a
	 * body the caller has not asked for must never be what blocks the caller forever.
	 */
	private static final class ResponseBodyDrain implements HttpResponseInterceptor {
		private final Exchange exchange;

		private final int maxBufferedBytes;

		private final long budgetMillis;

		ResponseBodyDrain(Exchange exchange, int maxBufferedBytes, long budgetMillis) {
			this.exchange = exchange;
			this.maxBufferedBytes = maxBufferedBytes;
			this.budgetMillis = budgetMillis;
		}

		@Override
		public void process(HttpResponse response, HttpContext context)
				throws HttpException, IOException {
			Closeable closeable = (response instanceof Closeable) ? (Closeable) response : null;
			//nothing that can throw may precede this: an Error out of this method is not one
			//ProtocolExec closes the response for, and the record is what leaves a cleaner able to.
			//A cast cannot throw; every call belongs below.
			exchange.bodyOnSocket(closeable);
			HttpEntity body = response.getEntity();
			if (body == null || !body.isStreaming()) {
				//nothing bound to the socket: the exec chain has already released it
				exchange.bodyReleased(closeable);
				return;
			}
			if (budgetMillis <= 0) {
				//nothing to mark: the state already says the body is on the socket, and
				//streamedUnreadCount reports it if the caller then walks away from it
				return;
			}
			long framedLength = body.getContentLength();
			ByteArrayOutputStream head = newBuffer(framedLength);
			try {
				InputStream in = body.getContent();
				if (readWithinLimit(in, head, maxBufferedBytes,
						System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis))) {
					response.setEntity(withMetadataOf(body,
							new BufferedBody(head.toByteArray(), framedLength)));
					closeQuietly(in);
					//that EOF already released the holder (ResponseEntityProxy.eofDetected), and
					//ConnectionHolder's release is CAS-guarded, so this close is a no-op whatever the
					//reuse strategy — kept so the branch does not lean on Apache's release ordering
					closeQuietly(closeable);
					exchange.bodyReleased(closeable);
				} else {
					InputStream whole = new SequenceInputStream(
							new ByteArrayInputStream(head.toByteArray()), in);
					response.setEntity(withMetadataOf(body,
							new InputStreamEntity(whole, framedLength)));
				}
			} catch (IOException e) {
				//a broken body used to surface when the caller read it, and still should; hand on
				//both what did arrive and the failure that stopped it
				response.setEntity(withMetadataOf(body,
						new FailedBody(head.toByteArray(), e, framedLength)));
				//unlike its twin above, this close can be the only one there is. Swallowing the
				//failure is what makes it so: ProtocolExec never sees the exception and so never
				//closes the response, and a getContent() that threw before any EofSensorInputStream
				//existed left nothing else holding the socket either
				closeQuietly(closeable);
				exchange.bodyReleased(closeable);
			}
		}

		//a declared length is only ever allocated when it fits the limit: the number comes off the
		//wire, so sizing a buffer to a claimed 2 GB would hand a remote server an OutOfMemoryError
		//to fire at will, and the (int) cast below is only sound inside that range — a larger claim
		//truncates or turns negative. Past the limit, and for a chunked body with no length at all,
		//the buffer grows by doubling instead — which is what makes peak heap a multiple of the limit.
		private ByteArrayOutputStream newBuffer(long framedLength) {
			if (framedLength > 0 && framedLength <= maxBufferedBytes) {
				return new ByteArrayOutputStream((int) framedLength);
			}
			return new ByteArrayOutputStream();
		}

		//true when the body ended within limit bytes, all of it now in head; false when it did not,
		//head then holding what had arrived by then — the first limit + 1 bytes of it, or less if
		//the deadline came first. Giving up on the deadline costs nothing but the buffering: the
		//caller is handed the prefix plus the rest of the socket either way.
		private static boolean readWithinLimit(InputStream in, ByteArrayOutputStream head, int limit,
			long deadlineNanos) throws IOException {
			byte[] chunk = new byte[8192];
			long read = 0;
			while (read <= limit) {
				//subtraction, not comparison: it stays correct across a nanoTime wrap
				if (System.nanoTime() - deadlineNanos >= 0) {
					return false;
				}
				int n = in.read(chunk, 0, (int) Math.min(chunk.length, limit + 1L - read));
				if (n < 0) {
					return true;
				}
				head.write(chunk, 0, n);
				read += n;
			}
			return false;
		}

		//the replacement has to carry the original Content-Type and Content-Encoding, so that
		//ResponseContentEncoding still decompresses and still strips those headers; dropping them
		//would leave Content-Encoding in place and wake up the gzip path in TransportHttp
		private static <T extends AbstractHttpEntity> T withMetadataOf(HttpEntity original,
				T replacement) {
			replacement.setContentType(original.getContentType());
			replacement.setContentEncoding(original.getContentEncoding());
			return replacement;
		}
	}

	/**
	 * A buffered response body that keeps reporting the length the wire framed it with rather than
	 * the buffer's own size. ResponseContentEncoding skips a body whose entity reports zero, so a
	 * chunked response that carried Content-Encoding and turned out to be empty would otherwise keep
	 * that header and have TransportHttp gzip-wrap an empty stream. Reporting the framed length keeps
	 * that decision identical to the one taken on the entity this replaces.
	 * <p>
	 * The two figures only ever differ for a chunked body, which frames no length at all: a declared
	 * length that the body then falls short of never reaches here, because reading such a body to
	 * its end raises ConnectionClosedException, which lands the exchange on {@link FailedBody}
	 * instead. Nothing on this path closes a body early — the one close is of a body already read
	 * whole — and nothing should start to: that close is what makes the release deterministic.
	 */
	private static final class BufferedBody extends ByteArrayEntity {
		private final long framedLength;

		BufferedBody(byte[] body, long framedLength) {
			super(body);
			this.framedLength = framedLength;
		}

		@Override
		public long getContentLength() {
			return framedLength;
		}
	}

	/**
	 * Hands on the bytes that did arrive, then the failure that stopped the rest.
	 * <p>
	 * Unlike {@link BufferedBody} this really does report a length it cannot deliver — the one the
	 * response declared, against a prefix of the body. It has to: reporting the prefix's length
	 * would put a zero in front of ResponseContentEncoding for a body that died before its first
	 * byte, where the chunked entity being replaced reported -1, and the header-stripping would
	 * differ from the unbroken case for no good reason.
	 * <p>
	 * What that costs is a size hint nobody should trust. {@code EntityUtils.toByteArray} allocates
	 * {@code new ByteArrayBuffer((int) getContentLength())} up front, capped only at
	 * {@code Integer.MAX_VALUE} with negatives falling back to a default — so calling it on this
	 * entity would allocate whatever the response claimed, up to 2 GB, to hold a prefix. No caller
	 * does today, and none should be added: read the stream instead.
	 */
	private static final class FailedBody extends AbstractHttpEntity {
		private final byte[] head;

		private final IOException failure;

		private final long framedLength;

		FailedBody(byte[] head, IOException failure, long framedLength) {
			this.head = head;
			this.failure = failure;
			this.framedLength = framedLength;
		}

		@Override
		public boolean isRepeatable() {
			return false;
		}

		@Override
		public long getContentLength() {
			return framedLength;
		}

		@Override
		public InputStream getContent() {
			return new FailedBodyStream(head, failure);
		}

		@Override
		public void writeTo(OutputStream outStream) throws IOException {
			outStream.write(head);
			throw failure;
		}

		@Override
		public boolean isStreaming() {
			return false;
		}
	}

	private static final class FailedBodyStream extends InputStream {
		private final byte[] head;

		private final IOException failure;

		private int next;

		FailedBodyStream(byte[] head, IOException failure) {
			this.head = head;
			this.failure = failure;
		}

		@Override
		public int read() throws IOException {
			if (next < head.length) {
				return head[next++] & 0xff;
			}
			throw failure;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			if (next >= head.length) {
				throw failure;
			}
			int n = Math.min(len, head.length - next);
			System.arraycopy(head, next, b, off, n);
			next += n;
			return n;
		}

		@Override
		public int available() {
			return head.length - next;
		}
	}

	/**
	 * Notes down that a body left on the socket reached its end or was closed. The stream underneath
	 * releases the connection on its own; what it cannot do is tell the exchange that it happened,
	 * and the exchange is what the cleaners read.
	 */
	private static final class TrackedBodyStream extends FilterInputStream {
		private final Exchange exchange;

		TrackedBodyStream(InputStream in, Exchange exchange) {
			super(in);
			this.exchange = exchange;
		}

		//the fence in all three: this stream's cleanup action is registered on the exchange, not on
		//the stream, so the stream may be collected while one of its own methods is still running
		//— the only touch of this after the delegated call is a final field, which may be hoisted
		//above it. A cleaner running in that window would close the socket under an active read
		//and record the orderly close as an abandonment. Same hazard getInputStream() fences.
		@Override
		public int read() throws IOException {
			try {
				int b = super.read();
				if (b < 0) {
					exchange.bodyFinished();
				}
				return b;
			} finally {
				Reference.reachabilityFence(this);
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			try {
				int n = super.read(b, off, len);
				if (n < 0) {
					exchange.bodyFinished();
				}
				return n;
			} finally {
				Reference.reachabilityFence(this);
			}
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				try {
					exchange.bodyFinished();
				} finally {
					Reference.reachabilityFence(this);
				}
			}
		}
	}
}
