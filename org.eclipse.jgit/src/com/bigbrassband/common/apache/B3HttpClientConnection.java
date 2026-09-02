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
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
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
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
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
 * what most of the file is, spread over the nested types below:
 * <ul>
 * <li>{@code Exchange} holds the state of one exchange and renders the single verdict the
 * counters are told about. It deliberately reaches neither the connection nor the body stream,
 * which is what lets a cleaner hold it.
 * <li>{@code NonOkBodyCloser} is a response interceptor that closes the connection of every
 * response other than 200, before the caller sees it. Nothing reads such a body, so nothing is
 * read off the wire first — the socket comes back at once.
 * <li>{@code DiscardedBody} is the replacement entity it installs, so that a caller which does
 * ask for one of those bodies is told it is gone rather than handed an empty one.
 * <li>{@code TrackedBodyStream} wraps a body handed to the caller and tells the exchange when
 * that body reaches its end or is closed.
 * </ul>
 * <p>
 * <b>Three places can render the verdict, and one would not do.</b> {@code TransportHttp} drops
 * the connection object as soon as it holds the body stream, so on every fetch this object
 * becomes unreachable while its body is still being read. A single cleanup action on the
 * connection would therefore count each real pack fetch as abandoned. Instead the exchange is
 * settled by whichever comes first: the exec chain returning, for a response already closed
 * unread; the body stream reaching its end or being closed; or collection — of the connection,
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
	private static final AtomicLong initCount = new AtomicLong(0);
	private static final AtomicLong closeCount = new AtomicLong(0);
	private static final AtomicLong abandonedCount = new AtomicLong(0);
	private static final AtomicLong streamedUnreadCount = new AtomicLong(0);
	private static final AtomicLong streamAbandonedCount = new AtomicLong(0);

	private final B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
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
	 * @return number of exchanges that released their connection since last call (resets counter).
	 *         Both ways of releasing land here and are not told apart: a body the caller read to
	 *         its end, and a response other than 200 whose connection was closed unread.
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
	 *         Nor does it pair off exactly against {@link #getInitCount()}. Four kinds of
	 *         connection count as an init and then report nothing: one whose URL fails to parse,
	 *         one simply constructed and dropped, one built around a caller-supplied
	 *         {@code CloseableHttpClient}, which carries none of the interceptor this bookkeeping
	 *         rides on and so goes unreported even though it did issue a request, and one whose
	 *         request never came back with a response at all — connection refused, DNS or TLS
	 *         failure, a timeout on the status line — which leaves the exchange where it
	 *         started and reports nothing when collected. None of the four left a socket open, so
	 *         the residual is not a leak; but the last of them grows with the failure rate, so
	 *         against an unreachable host it is not small either.
	 */
	public static long getAbandonedCount() {
		return abandonedCount.getAndSet(0L);
	}

	/**
	 * @return number of responses whose body was left on the socket and was then never read at
	 *         all, since last call (resets counter).
	 *         <p>
	 *         Only a 200 can land here: every other status has its connection closed before the
	 *         caller sees the response. So a body counted here is one the caller asked for and
	 *         walked away from without reading — which no setting answers, and which nothing in
	 *         jgit's {@code TransportHttp} does today. A non-zero count names a caller to look at,
	 *         not a number to change.
	 */
	public static long getStreamedUnreadCount() {
		return streamedUnreadCount.getAndSet(0L);
	}

	/**
	 * @return number of bodies handed to a caller and then let go before their end, since last call
	 *         (resets counter).
	 *         <p>
	 *         "Let go" is as much as this can tell. A caller walking away from a body it no longer
	 *         wants and a body breaking in mid-flight and being dropped where it broke are one
	 *         event here: in both the stream stopped short of its end and was collected rather
	 *         than closed. The socket was released either way, so this is a limit on how precisely
	 *         the count describes what happened, not a claim that anything leaked — but it does
	 *         mean a spell of torn connections raises this count, and {@link #getAbandonedCount()}
	 *         with it, while every caller behaved.
	 */
	public static long getStreamAbandonedCount() {
		return streamAbandonedCount.getAndSet(0L);
	}

	private CloseableHttpClient getClient() {
		if (client == null) {
			HttpClientBuilder clientBuilder = HttpClients.custom();
			clientBuilder.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE);
			//first, so the entity it replaces is the one that came off the wire rather than the
			//decompressing wrapper ResponseContentEncoding puts around it
			clientBuilder.addInterceptorFirst(new NonOkBodyCloser(exchange));
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
	 * @throws MalformedURLException MalformedURLException
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory) throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, null, httpClientConnectionManagerFactory);
	}

	/**
	 *
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @throws MalformedURLException MalformedURLException
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory)
			throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, proxy, null, httpClientConnectionManagerFactory);
	}

	/**
	 *
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param cl cl
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @throws MalformedURLException MalformedURLException
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, CloseableHttpClient cl, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory)
			throws MalformedURLException {
		initCount.incrementAndGet();
		Exchange onCollection = this.exchange;
		//a cleanup action able to reach this connection would keep it alive for good, turning the
		//socket leak into a memory leak — hence the exchange, and never this, as the receiver
		CLEANER.register(this, onCollection::connectionGone);
		this.client = cl;
		this.url = new URL(urlStr);
		this.proxy = proxy;
		this.httpClientConnectionManagerFactory=httpClientConnectionManagerFactory;

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
			//be told either way: a hop closed unread just before the throw would otherwise be left
			//looking like a body still on the socket, i.e. reported as abandoned
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

		//BODY_TAKEN is deliberately not included: a second getInputStream() would otherwise
		//build a second stream over the same socket and register a second cleaner, and collecting
		//that one closes the response under the first stream while the caller is still reading it.
		boolean isBodyOnSocket() {
			return state.get() == State.BODY_ON_SOCKET;
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

		//the exec chain has returned, so no further hop can revise the verdict; a response already
		//closed unread is counted here rather than waiting to be collected
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
	 * Closes the connection of any response other than 200, before the caller sees it.
	 * <p>
	 * Nothing reads such a body. jgit's {@code TransportHttp} throws on the status line — from
	 * {@code openResponse} on every service call, and from each {@code case} of {@code connect}
	 * that is not {@code HTTP_OK} — and builds its message out of the status line rather than the
	 * body. So there is nothing here to preserve, and reading the body off the wire first would
	 * only make the caller wait for bytes on their way to being discarded.
	 * <p>
	 * A 200 is left alone entirely: that body is the one the caller asked for, and the caller's
	 * own reading is what releases its socket.
	 * <p>
	 * So is a body framed as zero bytes, whatever its status. httpcore gives such a response
	 * {@code EmptyInputStream.INSTANCE} (BHttpConnectionBase:210-211), which
	 * {@code BasicHttpEntity.isStreaming} reports as not streaming, so nothing is bound to the
	 * socket and the exec chain has released it already. The caller then gets the real body, empty
	 * and whole — there is nothing here to discard.
	 * <p>
	 * Runs once per hop, so a redirect chain resolved inside httpclient reaches it for every
	 * response in the chain: the 3xx hops are closed here, the final 200 is not. That is the same
	 * socket-per-hop count as without this interceptor, since httpclient leases again for each hop
	 * and NoConnectionReuseStrategy closes every socket regardless.
	 */
	private static final class NonOkBodyCloser implements HttpResponseInterceptor {
		private final Exchange exchange;

		NonOkBodyCloser(Exchange exchange) {
			this.exchange = exchange;
		}

		@Override
		public void process(HttpResponse response, HttpContext context) {
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
			if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
				return;
			}
			//the exact code, not a 2xx range: the sign-in page this class was written for arrives
			//as 203, and every status but 200 is one TransportHttp throws on
			response.setEntity(withMetadataOf(body, new DiscardedBody(body.getContentLength())));
			//closing the response, never the body stream. HttpResponseProxy.close() reaches
			//ConnectionHolder.close() -> releaseConnection(false) -> managedConn.close(). The body
			//stream would instead reach ResponseEntityProxy.streamClosed, which closes the stream
			//it wraps — and ContentLengthInputStream.close() reads the whole remainder to discard
			//it, which is the wait this interceptor exists to avoid
			closeQuietly(closeable);
			exchange.bodyReleased(closeable);
		}

		//the replacement has to carry the original Content-Type and Content-Encoding, so that
		//ResponseContentEncoding still strips those headers; dropping them would leave
		//Content-Encoding in place and wake up the gzip path in TransportHttp
		private static <T extends AbstractHttpEntity> T withMetadataOf(HttpEntity original,
				T replacement) {
			replacement.setContentType(original.getContentType());
			replacement.setContentEncoding(original.getContentEncoding());
			return replacement;
		}
	}

	/**
	 * Stands in for a body closed unread, and says so to anyone who asks for it.
	 * <p>
	 * It reports the length the body was framed with rather than zero, so that
	 * ResponseContentEncoding takes the decision it would have taken on the entity being replaced:
	 * that class skips its work — leaving Content-Encoding in place — only for a length of exactly
	 * 0, and TransportHttp would then gzip-wrap an empty stream.
	 * <p>
	 * {@code isStreaming()} is false, and load-bearing: RedirectExec calls
	 * {@code EntityUtils.consume} on every hop it follows, and that reads the content only for a
	 * streaming entity. Reporting true would send it reading a body whose socket is already gone.
	 */
	private static final class DiscardedBody extends AbstractHttpEntity {
		private final long framedLength;

		DiscardedBody(long framedLength) {
			this.framedLength = framedLength;
		}

		@Override
		public boolean isRepeatable() {
			return false;
		}

		@Override
		public boolean isStreaming() {
			return false;
		}

		@Override
		public long getContentLength() {
			return framedLength;
		}

		@Override
		public InputStream getContent() throws IOException {
			throw discarded();
		}

		@Override
		public void writeTo(OutputStream outStream) throws IOException {
			throw discarded();
		}

		private static IOException discarded() {
			return new IOException("the body of this response was closed unread: nothing reads " //$NON-NLS-1$
					+ "the body of a response other than 200"); //$NON-NLS-1$
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
