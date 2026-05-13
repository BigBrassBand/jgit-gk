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
package org.eclipse.jgit.transport.http.apache;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
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
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.apache.internal.HttpApacheText;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.TemporaryBuffer.LocalFile;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
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
 * A {@link HttpConnection} which uses {@link HttpClient}
 *
 * @since 3.3
 */
public class B3HttpClientConnection implements HttpConnection {
	private final B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
	private HttpClient client;

	private URL url;

	private HttpUriRequest req;

	private HttpResponse resp = null;

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
	 * @param credentialsProvider credentialsProvider
	 */
	public void setCredentialsProvider(CredentialsProvider credentialsProvider) {
		this.credentialsProvider = credentialsProvider;
	}

	private HttpClient getClient() {
		if (client == null) {
			HttpClientBuilder clientBuilder = HttpClients.custom();
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
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @throws MalformedURLException stub
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory) throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, null, httpClientConnectionManagerFactory);
	}

	/**
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @throws MalformedURLException stub
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory)
			throws MalformedURLException {
		this(urlStr, connectTimeoutSeconds, proxy, null, httpClientConnectionManagerFactory);
	}

	/**
	 * @param urlStr urlStr
	 * @param connectTimeoutSeconds connectTimeoutSeconds
	 * @param proxy proxy
	 * @param cl cl
	 * @param httpClientConnectionManagerFactory httpClientConnectionManagerFactory
	 * @throws MalformedURLException stub
	 */
	public B3HttpClientConnection(String urlStr, int connectTimeoutSeconds, Proxy proxy, HttpClient cl, B3HttpClientConnectionFactory.HttpClientConnectionManagerFactory httpClientConnectionManagerFactory)
			throws MalformedURLException {
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

	/**
	 * @return Returns int
	 * @throws IOException stub
	 */
	public int getResponseCode() throws IOException {
		execute();
		return resp.getStatusLine().getStatusCode();
	}

	/**
	 * @return Returns url
	 */
	public URL getURL() {
		return url;
	}

	/**
	 * @return Returns string
	 * @throws IOException stub
	 */
	public String getResponseMessage() throws IOException {
		execute();
		return resp.getStatusLine().getReasonPhrase();
	}

	private void execute() throws IOException {
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

		if (entity == null) {
			resp = getClient().execute(req,context);
			return;
		}

		try {
			if (req instanceof HttpEntityEnclosingRequest) {
				HttpEntityEnclosingRequest eReq = (HttpEntityEnclosingRequest) req;
				eReq.setEntity(entity);
			}
			resp = getClient().execute(req,context);
		} finally {
			entity.close();
			entity = null;
		}
	}

	/**
	 * @return Returns map
	 */
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

	/**
	 * @param name
	 *            the keyword by which the request is known (e.g., "
	 *            <code>Accept</code>").
	 * @param value
	 *            the value associated with it.
	 */
	public void setRequestProperty(String name, String value) {
		req.addHeader(name, value);
	}

	/**
	 * @param method
	 *            the HTTP method
     */
	public void setRequestMethod(String method) {
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

	/**
	 * @param usecaches
	 *            a <code>boolean</code> indicating whether or not to allow
	 *            caching
	 */
	public void setUseCaches(boolean usecaches) {
		// not needed
	}

	/**
	 * @param timeout
	 *            an <code>int</code> that specifies the connect timeout value
	 *            in milliseconds
	 */
	public void setConnectTimeout(int timeout) {
		if(ignoreConnectTimeoutMillisecondsSets)
			return;
		this.connectTimeoutMilliseconds = timeout;
	}

	/**
	 * @param readTimeout
	 *            an <code>int</code> that specifies the timeout value to be
	 *            used in milliseconds
	 */
	public void setReadTimeout(int readTimeout) {
		this.readTimeoutMilliseconds = readTimeout;
	}

	/**
	 * @return Returns string
	 */
	public String getContentType() {
		HttpEntity responseEntity = resp.getEntity();
		if (responseEntity != null) {
			Header contentType = responseEntity.getContentType();
			if (contentType != null)
				return contentType.getValue();
		}
		return null;
	}

	/**
	 * @return Returns InputString
	 * @throws IOException stub
	 */
	public InputStream getInputStream() throws IOException {
		return resp.getEntity().getContent();
	}

	// will return only the first field

	/**
	 * @param name
	 *            the name of a header field.
	 * @return Returns string
	 */
	public String getHeaderField(@NonNull String name) {
		Header header = resp.getFirstHeader(name);
		return (header == null) ? null : header.getValue();
	}

	/**
	 * @param name
	 *            the name of a header field.
	 * @return Returns list
	 */
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

	/**
	 * @return Returns int
	 */
	public int getContentLength() {
		Header contentLength = resp.getFirstHeader("content-length"); //$NON-NLS-1$
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

	/**
	 * @param followRedirects
	 *            a <code>boolean</code> indicating whether or not to follow
	 *            HTTP redirects.
	 */
	public void setInstanceFollowRedirects(boolean followRedirects) {
		this.followRedirects = followRedirects;
	}

	/**
	 * @param dooutput
	 *            the new value.
	 */
	public void setDoOutput(boolean dooutput) {
		// TODO: check whether we can really ignore this.
	}

	/**
	 * @param contentLength
	 *            The number of bytes which will be written to the OutputStream.
	 */
	public void setFixedLengthStreamingMode(int contentLength) {
		if (entity != null)
			throw new IllegalArgumentException();
		entity = new TemporaryBufferEntity(new LocalFile(null));
		entity.setContentLength(contentLength);
	}

	/**
	 * @return Returns OutputStream
	 * @throws IOException stub
	 */
	public OutputStream getOutputStream() throws IOException {
		if (entity == null)
			entity = new TemporaryBufferEntity(new LocalFile(null));
		return entity.getBuffer();
	}

	/**
	 * @param chunklen
	 *            The number of bytes to write in each chunk. If chunklen is
	 *            less than or equal to zero, a default value will be used.
	 */
	public void setChunkedStreamingMode(int chunklen) {
		if (entity == null)
			entity = new TemporaryBufferEntity(new LocalFile(null));
		entity.setChunked(true);
	}

	/**
	 * @return Returns string
	 */
	public String getRequestMethod() {
		return method;
	}

	/**
	 * @return Returns boolean
	 */
	public boolean usingProxy() {
		return isUsingProxy;
	}

	public void connect() throws IOException {
		execute();
	}

	/**
	 * @param hostnameverifier
	 *            a {@link javax.net.ssl.HostnameVerifier} object.
	 */
	public void setHostnameVerifier(final HostnameVerifier hostnameverifier) {
		this.hostnameverifier = new X509HostnameVerifier() {
			public boolean verify(String hostname, SSLSession session) {
				return hostnameverifier.verify(hostname, session);
			}

			public void verify(String host, String[] cns, String[] subjectAlts) {
				throw new UnsupportedOperationException(); // TODO message
			}

			public void verify(String host, X509Certificate cert) {
				throw new UnsupportedOperationException(); // TODO message
			}

			public void verify(String host, SSLSocket ssl) {
				hostnameverifier.verify(host, ssl.getSession());
			}
		};
	}

	/**
	 * @param km
	 *            the keymanager managing the key material used to authenticate
	 *            the local SSLSocket to its peer
	 * @param tm
	 *            the trustmanager responsible for managing the trust material
	 *            that is used when making trust decisions, and for deciding
	 *            whether credentials presented by a peer should be accepted.
	 * @param random
	 *            the source of randomness for this generator or null. See
	 *            {@link javax.net.ssl.SSLContext#init(KeyManager[], TrustManager[], SecureRandom)}
	 * @throws KeyManagementException stub
	 */
	public void configure(KeyManager[] km, TrustManager[] tm,
			SecureRandom random) throws KeyManagementException {
		getSSLContext().init(km, tm, random);
	}
}
