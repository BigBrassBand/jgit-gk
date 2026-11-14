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
import org.apache.http.HttpClientConnection;
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
import org.apache.http.conn.ConnectionRequest;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
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
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * <li>{@code NonOkBodyBuffer} is a response interceptor that reads the body of every response
 * other than 200, within a byte limit and a time budget, and releases the connection before the
 * caller sees it. The caller gets an in-memory copy, and {@link #getResponseMessage()} puts a
 * {@code text/plain} one after the reason phrase — the only part of a refusal jgit's
 * {@code TransportHttp} keeps.
 * <li>{@code CutShortBody} is the entity it installs when that read stops before the end, so that
 * a caller which asks for the body gets the prefix that was kept and then a
 * {@link CutShortBodyException}, rather than a prefix it would take for the whole.
 * <li>{@code TrackedBodyStream} wraps a body handed to the caller and tells the exchange when
 * that body reaches its end or is closed.
 * <li>{@code ClosingConnectionManager} wraps the connection manager and closes every connection
 * handed back to it, so no socket outlives the exchange it served in any manager.
 * </ul>
 * <p>
 * <b>Three places can render the verdict, and one would not do.</b> {@code TransportHttp} drops
 * the connection object as soon as it holds the body stream, so on every fetch this object
 * becomes unreachable while its body is still being read. A single cleanup action on the
 * connection would therefore count each real pack fetch as abandoned. Instead the exchange is
 * settled by whichever comes first: the exec chain returning, for a response already
 * released; the body stream reaching its end or being closed; or collection — of the connection,
 * or of the stream, two cleaners with the connection's deferring to the stream's while a body is
 * out. The connection also holds the stream it handed out, so the stream's collection cannot
 * precede the connection's — it coincides with it or follows it. A compare-and-set inside
 * {@code Exchange} keeps all of this to one verdict per connection, so a redirect chain that
 * released four sockets still reports once.
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
	/**
	 * The most of a non-200 body read into memory; a longer one is cut short at this many bytes,
	 * counted as they came off the wire. Past ten times the largest unread body measured in
	 * production, the 89,976-byte Azure DevOps sign-in page.
	 */
	public static final int MAX_NON_OK_BODY_BYTES = 1024 * 1024;

	private static final Cleaner CLEANER = Cleaner.create();
	private static final AtomicLong initCount = new AtomicLong(0);
	private static final AtomicLong closeCount = new AtomicLong(0);
	private static final AtomicLong abandonedCount = new AtomicLong(0);
	private static final AtomicLong streamedUnreadCount = new AtomicLong(0);
	private static final AtomicLong streamAbandonedCount = new AtomicLong(0);

	private final B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
	private final Exchange exchange = new Exchange();
	private TrackedBodyStream takenBody;
	//CloseableHttpClient rather than HttpClient because only its execute() returns a
	//CloseableHttpResponse, and the response is what has to be closed. The client itself is
	//never closed on purpose: the connection manager can come from an outside factory, and
	//HttpClientBuilder adds that manager's shutdown to the client's own closeable resources
	//unless it is marked shared — so closing the client here would stop a manager that other
	//connections are still using. Nothing leaks by leaving it open: ClosingConnectionManager
	//closes every connection as it is released, so no socket is ever parked in the manager.
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
	 * @return number of exchanges whose connection came back, since last call (resets counter).
	 *         Every way of getting there lands here and none is told apart — a body read to its
	 *         end, a body closed before it, a response other than 200 read whole or cut short, a response
	 *         with no streaming body to begin with, and a chain that threw over a body still on
	 *         the socket.
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
	 *         started and reports nothing when collected.
	 *         <p>
	 *         Only the third can leave a socket open: a body read to its end releases through
	 *         httpclient's own stream, but one left unread releases nothing and counts nothing, so
	 *         those are leaks this counter cannot see.
	 *         <p>
	 *         The fourth leaves no socket open and still widens the gap between init and close,
	 *         because it reports neither. Against an unreachable host it grows with the failure
	 *         rate, so a gap that is normally small need not stay small.
	 *         <p>
	 *         This counter changed definition without changing name. It used to be incremented by
	 *         a cleanup action registered unconditionally, so it tracked collected connections and
	 *         all but repeated {@link #getInitCount()}; it now counts only exchanges that ended
	 *         with a body still on the socket. A series spanning the change steps down for that
	 *         reason and not for a change in leaking.
	 */
	public static long getAbandonedCount() {
		return abandonedCount.getAndSet(0L);
	}

	/**
	 * @return number of responses whose body was left on the socket and was then never read at
	 *         all, since last call (resets counter).
	 *         <p>
	 *         On the httpclient this builds against, only a 200 reaches here: every other status
	 *         has its body read and its connection released before the caller sees the response,
	 *         because {@code
	 *         ProtocolExec} always hands the interceptor an {@code HttpResponseProxy} and that is
	 *         {@link Closeable}. A response that is not would take the one branch that closes
	 *         nothing, and could be counted here whatever its status.
	 *         <p>
	 *         So a body counted here is one the caller asked for and walked away from without
	 *         reading — which no setting answers, and which nothing in jgit's
	 *         {@code TransportHttp} does today. A non-zero count names a caller to look at, not a
	 *         number to change.
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
			//first, so the entity it replaces is the one that came off the wire rather than the
			//decompressing wrapper ResponseContentEncoding puts around it
			clientBuilder.addInterceptorFirst(new NonOkBodyBuffer(exchange, readBudgetMillis()));
			HttpClientConnectionManager connectionManager = null;
			if(httpClientConnectionManagerFactory!=null)
				connectionManager = httpClientConnectionManagerFactory.getConnectionManager();
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
				connectionManager = new BasicHttpClientConnectionManager(registry);
			}
			if (connectionManager == null) {
				//the socket factories the builder's own default would have, without its pool: a manager
				//the builder makes is one this class could not wrap
				connectionManager = new BasicHttpClientConnectionManager();
			}
			clientBuilder.setConnectionManager(new ClosingConnectionManager(connectionManager));
			configBuilder.setCookieSpec(CookieSpecs.STANDARD);
			clientBuilder.setDefaultRequestConfig(configBuilder.build());

			client = clientBuilder.build();
		}

		return client;
	}

	//one read timeout for the whole body: the socket timeout bounds a single read, not the body,
	//since read() returns as soon as any byte arrives. Not a multiple of it: jgit sets the read
	//timeout from the transport timeout, minutes long where the appserver configures it, and an
	//error body nobody may ask for must not hold an indexing thread for several of those.
	//Zero means the body is not read. A read timeout of zero reaches setSocketTimeout as "wait
	//forever", and jgit passes exactly that when the repository configures no timeout (RemoteConfig
	//reads KEY_TIMEOUT with a default of 0), so a single read need never return
	private long readBudgetMillis() {
		if (readTimeoutMilliseconds == null || readTimeoutMilliseconds <= 0) {
			return 0L;
		}
		return readTimeoutMilliseconds;
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
	 * @param cl a client to use instead of the one this class builds. Unsupported for anything
	 *            that cares about sockets: the bookkeeping rides on an interceptor registered while
	 *            building the client, so a client supplied here carries none of it. A body read to
	 *            its end on such an exchange still releases, because httpclient's own stream does
	 *            that without help; a body left unread or dropped releases nothing and reaches no
	 *            counter. The connection manager also outlives this object, so with a
	 *            single-connection manager that unread body never returns the leased entry and the
	 *            next lease blocks.
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

	/**
	 * The reason phrase, followed by the body of a response other than 200 when that body is
	 * {@code text/plain}. jgit's {@code TransportHttp} builds its exception text from this rather
	 * than from the body, so this is how a provider's explanation of a refusal reaches a caller —
	 * Azure DevOps Services, for one, says only "Bad Request" in the status line and names the
	 * unsupported filter in the body. A body that was cut short contributes the prefix that was
	 * kept, followed by the {@link CutShortBodyException} message in square brackets.
	 */
	@Override
	public String getResponseMessage() throws IOException {
		execute();
		String reason = resp.getStatusLine().getReasonPhrase();
		String bodyText = nonOkBodyText();
		if (bodyText.isEmpty()) {
			return reason;
		}
		return (reason == null || reason.isEmpty()) ? bodyText : reason + ": " + bodyText; //$NON-NLS-1$
	}

	//text/plain only: that is the form git servers give a refusal in, while an HTML sign-in page or
	//an error page would put a whole document of markup into every log line carrying the message
	private String nonOkBodyText() {
		HttpEntity body = resp.getEntity();
		//a non-streaming entity is the in-memory copy NonOkBodyBuffer installed; a 200 body is still
		//on its socket and belongs to whoever takes the stream
		if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK || body == null
				|| body.isStreaming()) {
			return ""; //$NON-NLS-1$
		}
		ContentType type = ContentType.getLenient(body);
		if (type == null || !ContentType.TEXT_PLAIN.getMimeType().equalsIgnoreCase(type.getMimeType())) {
			return ""; //$NON-NLS-1$
		}
		ByteArrayOutputStream decoded = new ByteArrayOutputStream();
		String cutShort = null;
		try (InputStream in = body.getContent()) {
			in.transferTo(decoded);
		} catch (IOException e) {
			//a body cut short hands over its prefix and then throws; a gzipped prefix fails in the
			//decompressor instead. Either way what was decoded is a prefix and has to say so
			cutShort = (e instanceof CutShortBodyException) ? e.getMessage()
					: "the body could not be read to its end: " + e; //$NON-NLS-1$
		}
		//the declared charset when there is one and it is known; UTF-8 otherwise
		Charset charset = type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
		String text = new String(decoded.toByteArray(), charset).trim();
		if (cutShort == null) {
			return text;
		}
		return text.isEmpty() ? "[" + cutShort + "]" : text + " [" + cutShort + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
			//a null resp is the only sign of a failed chain available here. What exchangeFailed()
			//saves is the opposite hop: one whose body is still on the socket when the chain threw,
			//which the exec chain closes on its way out and which would otherwise be collected and
			//reported as abandoned
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
			//the same instance on every later call: a second TrackedBodyStream would carry a second
			//cleaner, and an untracked stream would let the first one's collection report an
			//abandonment against an exchange that ended cleanly
			if (takenBody != null) {
				return takenBody;
			}
			if (exchange.isBodyOnSocket()) {
				//JGit drops the connection as soon as it has the stream (TransportHttp does
				//in.add(openInputStream(conn)); conn = null), so from here on the body's fate is
				//decided by the stream's own lifetime, not by ours
				TrackedBodyStream body = new TrackedBodyStream(resp.getEntity().getContent(), exchange);
				Exchange onCollection = this.exchange;
				CLEANER.register(body, onCollection::bodyStreamGone);
				takenBody = body;
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
		this.hostnameverifier = new CallerHostnameVerifier(hostnameverifier);
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
	 * javac gives an anonymous class a reference to its enclosing instance whether or not the body uses
	 * one, and {@link #getClient()} puts this verifier inside the connection manager — from where a
	 * response reaches back through its {@code ConnectionHolder}, the manager and the socket factory
	 * registry. An anonymous verifier would close that circle onto the connection whose collection
	 * {@link Exchange}'s cleanup actions wait for, and no cleaner would ever run.
	 */
	private static final class CallerHostnameVerifier implements X509HostnameVerifier {
		private final HostnameVerifier delegate;

		CallerHostnameVerifier(HostnameVerifier delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean verify(String hostname, SSLSession session) {
			return delegate.verify(hostname, session);
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
			delegate.verify(host, ssl.getSession());
		}
	}

	/**
	 * Closes every connection handed back to the manager it wraps, so that whatever the reuse
	 * strategy decided, no socket of this class stays in a manager after release.
	 * <p>
	 * The strategy is httpclient's default because NTLM needs a socket kept alive: httpclient
	 * answers the challenge itself inside one {@code execute()}, and the server accepts the answer
	 * only on the socket that carried the challenge. Between those legs the connection stays leased;
	 * it reaches {@code releaseConnection} only once the exchange is over, which is where this
	 * closes it. The price of keeping it is that such a challenge body is read to its end before
	 * the next leg rather than dropped.
	 * <p>
	 * Closing on release rather than closing idle connections once {@code execute()} returns covers
	 * a body the caller finishes later, and leaves alone the idle connections of other users of a
	 * shared manager. Static for the reason {@code CallerHostnameVerifier} gives: a response reaches
	 * this through its {@code ConnectionHolder}.
	 */
	private static final class ClosingConnectionManager implements HttpClientConnectionManager {
		private final HttpClientConnectionManager delegate;

		ClosingConnectionManager(HttpClientConnectionManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public ConnectionRequest requestConnection(HttpRoute route, Object state) {
			return delegate.requestConnection(route, state);
		}

		@Override
		public void releaseConnection(HttpClientConnection conn, Object newState,
				long validDuration, TimeUnit timeUnit) {
			try {
				closeQuietly(conn);
			} finally {
				delegate.releaseConnection(conn, newState, validDuration, timeUnit);
			}
		}

		@Override
		public void connect(HttpClientConnection conn, HttpRoute route, int connectTimeout,
				HttpContext context) throws IOException {
			delegate.connect(conn, route, connectTimeout, context);
		}

		@Override
		public void upgrade(HttpClientConnection conn, HttpRoute route, HttpContext context)
				throws IOException {
			delegate.upgrade(conn, route, context);
		}

		@Override
		public void routeComplete(HttpClientConnection conn, HttpRoute route, HttpContext context)
				throws IOException {
			delegate.routeComplete(conn, route, context);
		}

		@Override
		public void closeIdleConnections(long idletime, TimeUnit timeUnit) {
			delegate.closeIdleConnections(idletime, timeUnit);
		}

		@Override
		public void closeExpiredConnections() {
			delegate.closeExpiredConnections();
		}

		@Override
		public void shutdown() {
			delegate.shutdown();
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
			//a no-op on both paths that reach here, and kept for the one that might not: the stream
			//underneath releases the holder itself, on EOF through eofDetected and on close through
			//streamClosed, and closing an already-released holder does nothing. What this cannot be is
			//the release itself — a caller left holding a body whose socket we closed reads a failure
			//off it, see TrackedBodyStream.close()
			closeQuietly(response);
			state.set(State.RELEASED);
			account();
		}

		//the exec chain has returned, so no further hop can revise the verdict; a response already
		//released is counted here rather than waiting to be collected
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
			State current = state.get();
			if (current == State.RELEASED) {
				//the same immediate verdict exchangeSettled() gives: both are called from the
				//finally of execute(), so a hop that already released has nothing left to wait for
				account();
				return;
			}
			if (current != State.BODY_ON_SOCKET) {
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
		//The closing sits outside that compare-and-set because closing an already-released
		//holder is a no-op — there is nothing for a guard to save.
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
			//frees the holder, the manager and the socket factory registry now rather than when the last
			//of the two cleanup actions runs — until then the cleaner's own list holds this exchange.
			//A close racing this one finds null and skips a close that already happened.
			response = null;
		}
	}

	/**
	 * Reads the body of any response other than 200 and releases the connection, before the caller
	 * sees it; the caller gets an in-memory copy in its place.
	 * <p>
	 * jgit's {@code TransportHttp} throws on the status line — from {@code openResponse} on every
	 * service call, and from each {@code case} of {@code connect} that is not {@code HTTP_OK} — and
	 * builds its message from {@link #getResponseMessage()}, never from the body. A provider that
	 * explains a refusal does so in the body, so the body has to be read here, while the socket is
	 * still ours, for {@code getResponseMessage()} to pass it on.
	 * <p>
	 * The read is bounded twice, because it waits on a body nobody may ever ask for: by
	 * {@link #MAX_NON_OK_BODY_BYTES}, counted as the bytes came off the wire, and by a time budget
	 * of one read timeout. A body that hits either is cut short: the response is closed, which
	 * releases the socket without reading the remainder, and {@code CutShortBody} hands on the
	 * prefix that was kept followed by a {@link CutShortBodyException} saying why. A body that
	 * breaks in mid-flight ends the same way. Without a finite read timeout nothing bounds a single
	 * read, so the body is not read at all and is cut short at zero bytes.
	 * <p>
	 * The copy is kept whatever its content type, so that any caller which asks for it gets the
	 * real body: {@code org.eclipse.jgit.lfs.SmudgeFilter} accepts 203 alongside 200 and parses that
	 * body as JSON, taking its connection from the same process-wide {@code HttpTransport} connection
	 * factory. Which part of it goes into the message is {@code getResponseMessage()}'s decision.
	 * <p>
	 * The fork's other consumer cannot reach this code at all: GIJ Data Center installs a
	 * same-named factory from {@code org.eclipse.jgit.transport.http.apache}, not this package, and
	 * pins the {@code -r-gk} line, where this package does not exist.
	 * <p>
	 * A 200 is left alone entirely: that body is the one the caller asked for, and the caller's
	 * own reading is what releases its socket.
	 * <p>
	 * So is a body framed as zero bytes, whatever its status, and by a route worth naming because a
	 * dependency bump can take it away. Since httpcore 4.4, {@code BHttpConnectionBase
	 * .createInputStream} answers a declared length of zero with {@code EmptyInputStream.INSTANCE}
	 * rather than an empty {@code ContentLengthInputStream}, and {@code BasicHttpEntity.isStreaming}
	 * singles that instance out as not streaming. So {@code ResponseEntityProxy.enchance} never
	 * wraps it, no connection holder is attached, and the exec chain has already released the
	 * socket. The caller then gets the real body — empty and whole, with nothing here to copy.
	 * <p>
	 * Runs once per hop, so a redirect chain resolved inside httpclient reaches it for every
	 * response in the chain: the 3xx hops are read here, the final 200 is not. That is the same
	 * socket-per-hop count as without this interceptor, since httpclient leases again for each hop
	 * and ClosingConnectionManager closes every socket as it is released.
	 */
	private static final class NonOkBodyBuffer implements HttpResponseInterceptor {
		private final Exchange exchange;

		private final long budgetMillis;

		NonOkBodyBuffer(Exchange exchange, long budgetMillis) {
			this.exchange = exchange;
			this.budgetMillis = budgetMillis;
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
			//the exact code, not a 2xx range: every status but 200 is one TransportHttp throws on
			int status = response.getStatusLine().getStatusCode();
			if (status == HttpStatus.SC_OK) {
				return;
			}
			long framedLength = body.getContentLength();
			if (budgetMillis <= 0) {
				if (closeable == null) {
					//nothing to close and nothing to bound a read by: leaving the real body in place
					//keeps the caller's own reading able to release the socket
					return;
				}
				cutShort(response, body, closeable, null, new CutShortBody(new byte[0],
						new CutShortBodyException(status, CutShortBodyException.Reason.NOT_READ, 0, null),
						framedLength));
				return;
			}
			ByteArrayOutputStream head = newBuffer(framedLength);
			InputStream in = null;
			CutShortBodyException.Reason stopped;
			IOException failure = null;
			try {
				in = body.getContent();
				stopped = read(in, head, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
			} catch (IOException e) {
				stopped = CutShortBodyException.Reason.READ_FAILED;
				failure = e;
			}
			if (stopped == null) {
				response.setEntity(withMetadataOf(body, new ByteArrayEntity(head.toByteArray())));
				//reading to the end has released the connection through httpclient's own stream, so
				//this close finds a released holder and does nothing; it stays for a stream that
				//reports its end without releasing
				closeQuietly(closeable);
				exchange.bodyReleased(closeable);
				return;
			}
			byte[] kept = Arrays.copyOf(head.toByteArray(), Math.min(head.size(), MAX_NON_OK_BODY_BYTES));
			cutShort(response, body, closeable, in, new CutShortBody(kept,
					new CutShortBodyException(status, stopped, kept.length, failure), framedLength));
		}

		private void cutShort(HttpResponse response, HttpEntity original, Closeable closeable,
				InputStream in, CutShortBody replacement) {
			response.setEntity(withMetadataOf(original, replacement));
			//the response rather than the body stream where there is one.
			//HttpResponseProxy.close() reaches ConnectionHolder.close() -> releaseConnection(false)
			//-> managedConn.close(). The body stream would instead reach
			//ResponseEntityProxy.streamClosed, which closes the stream it wraps — and
			//ContentLengthInputStream.close() reads the whole remainder to discard it, which is the
			//wait the bounds exist to cut off. Without a response to close, the stream is the only
			//handle left, and its close pays that wait one socket timeout per read
			closeQuietly(closeable != null ? closeable : in);
			exchange.bodyReleased(closeable);
		}

		//a declared length is only allocated up front when it fits the bound: the number comes off
		//the wire, and sizing a buffer to a claimed 2 GB would hand a remote server an
		//OutOfMemoryError to fire at will
		private static ByteArrayOutputStream newBuffer(long framedLength) {
			if (framedLength > 0 && framedLength <= MAX_NON_OK_BODY_BYTES) {
				return new ByteArrayOutputStream((int) framedLength);
			}
			return new ByteArrayOutputStream();
		}

		//null when the body ended within the bound, head then holding all of it; otherwise why the
		//read stopped, head holding at most MAX_NON_OK_BODY_BYTES + 1 bytes.
		//The deadline is only tested between reads, so the last read can still take a whole socket
		//timeout past it, and the worst case is two read timeouts. Bounding that read too would
		//mean handing setSocketTimeout a shrinking value, and zero there means "wait forever", not
		//"give up now".
		private static CutShortBodyException.Reason read(InputStream in, ByteArrayOutputStream head,
				long deadlineNanos) throws IOException {
			byte[] chunk = new byte[8192];
			long read = 0;
			while (read <= MAX_NON_OK_BODY_BYTES) {
				//subtraction, not comparison: it stays correct across a nanoTime wrap
				if (System.nanoTime() - deadlineNanos >= 0) {
					return CutShortBodyException.Reason.TIME_BUDGET;
				}
				int n = in.read(chunk, 0, (int) Math.min(chunk.length, MAX_NON_OK_BODY_BYTES + 1L - read));
				if (n < 0) {
					return null;
				}
				head.write(chunk, 0, n);
				read += n;
			}
			return CutShortBodyException.Reason.BYTE_LIMIT;
		}

		//the replacement has to carry the original Content-Type and Content-Encoding, so that
		//ResponseContentEncoding still strips those headers — and, for the copy, still decodes it;
		//dropping them would leave Content-Encoding in place and wake up the gzip path in
		//TransportHttp
		private static <T extends AbstractHttpEntity> T withMetadataOf(HttpEntity original,
				T replacement) {
			replacement.setContentType(original.getContentType());
			replacement.setContentEncoding(original.getContentEncoding());
			return replacement;
		}
	}

	/**
	 * Stands in for a body whose reading stopped before its end: its stream hands over the prefix
	 * that was kept, then throws the {@link CutShortBodyException} saying why, every time it is
	 * asked for.
	 * <p>
	 * It reports the length the body was framed with rather than the prefix's, so that
	 * ResponseContentEncoding takes the decision it would have taken on the entity being replaced:
	 * that class skips its work — leaving Content-Encoding in place — only for a length of exactly
	 * 0, and a body cut short at zero bytes would otherwise keep a Content-Encoding that describes
	 * nothing. The cost is a length nobody should size a buffer by: {@code EntityUtils.toByteArray}
	 * allocates whatever it claims.
	 * <p>
	 * {@code isStreaming()} is false, and load-bearing: RedirectExec calls
	 * {@code EntityUtils.consume} on every hop it follows, and that reads the content only for a
	 * streaming entity. Reporting true would send it reading a body whose socket is already gone.
	 */
	private static final class CutShortBody extends AbstractHttpEntity {
		private final byte[] prefix;

		private final CutShortBodyException failure;

		private final long framedLength;

		CutShortBody(byte[] prefix, CutShortBodyException failure, long framedLength) {
			this.prefix = prefix;
			this.failure = failure;
			this.framedLength = framedLength;
		}

		@Override
		public boolean isRepeatable() {
			return true;
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
		public InputStream getContent() {
			return new CutShortStream(prefix, failure);
		}

		@Override
		public void writeTo(OutputStream outStream) throws IOException {
			outStream.write(prefix);
			throw failure;
		}
	}

	private static final class CutShortStream extends InputStream {
		private final byte[] prefix;

		private final CutShortBodyException failure;

		private int next;

		CutShortStream(byte[] prefix, CutShortBodyException failure) {
			this.prefix = prefix;
			this.failure = failure;
		}

		@Override
		public int read() throws IOException {
			if (next < prefix.length) {
				return prefix[next++] & 0xff;
			}
			throw failure;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			if (next >= prefix.length) {
				throw failure;
			}
			int n = Math.min(len, prefix.length - next);
			System.arraycopy(prefix, next, b, off, n);
			next += n;
			return n;
		}

		@Override
		public int available() {
			return prefix.length - next;
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

		//the fence in every method below: this stream's cleanup action watches the stream but refers to
		//nothing in it — the receiver is the Exchange — so the stream may be collected while one of its
		//own methods is still running. The only touch of this after the delegated call is a final field,
		//which may be hoisted above it. A cleaner running in that window would close the socket under an
		//active read and record the orderly close as an abandonment. Same hazard getInputStream() fences.
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

		//super.close() before bodyFinished(), not after. super.close() reaches
		//ResponseEntityProxy.streamClosed, which drains whatever the caller left of the body so the
		//socket could be reused, and releases the connection in its own finally either way. Releasing
		//it first instead — to make that drain fail fast rather than read the remainder — leaves the
		//drain reading a socket we closed, and what that read returns is a property of the transport.
		//A plain socket raises SocketException, which streamClosed swallows once the holder is released;
		//an SSLSocket returns -1 — neither is specified, both measured on 17 — and both body framings
		//report that -1 as a body that ended wrong —
		//MalformedChunkCodingException, ConnectionClosedException — neither of which is swallowed, so
		//jgit surfaces it as a failed ls-remote.
		//The cost is paid by a caller that walks away from a 200 mid-body: closing it now waits out the
		//server's delivery of the remainder. TransportHttp.openResponse is the live case — it takes a
		//200, finds a content type it did not ask for, and closes the stream. A non-200 pays nothing here:
		//the interceptor has already read its body into memory, and the caller's stream is that copy
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

		@Override
		public long skip(long n) throws IOException {
			try {
				return super.skip(n);
			} finally {
				Reference.reachabilityFence(this);
			}
		}

		@Override
		public int available() throws IOException {
			try {
				return super.available();
			} finally {
				Reference.reachabilityFence(this);
			}
		}
	}
}
