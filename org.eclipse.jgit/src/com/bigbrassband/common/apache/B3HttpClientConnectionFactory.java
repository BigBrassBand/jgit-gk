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

import org.apache.http.conn.HttpClientConnectionManager;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;

/**
 * A factory returning instances of {@link B3HttpClientConnection}
 *
 * @since 3.3
 */
public class B3HttpClientConnectionFactory implements HttpConnectionFactory {

	/**
	 * Response bodies up to this size are buffered and the connection released before the caller
	 * sees the response, unless a different limit is passed to
	 * {@link #B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory, int, int)}.
	 * <p>
	 * The buffer is held per exchange in flight, and a chunked body reaches the limit by doubling,
	 * so peak heap is a small multiple of this figure times the number of concurrent requests.
	 */
	public static final int DEFAULT_MAX_BUFFERED_RESPONSE_BYTES = 1024 * 1024;

	/**
	 * HttpClientConnectionManagerFactory
	 */
	protected final HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
	private final int connectTimeoutSeconds;
	private final int maxBufferedResponseBytes;

	/**
	 * B3HttpClientConnectionFactory
	 */
	public B3HttpClientConnectionFactory() {
		this(null, 0, DEFAULT_MAX_BUFFERED_RESPONSE_BYTES);
	}

	/**
	 * B3HttpClientConnectionFactory.
	 *
	 * @param httpClientConnectionManagerFactory HttpClientConnectionManagerFactory
	 */
	public B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory httpClientConnectionManagerFactory) {
		this(httpClientConnectionManagerFactory, 0, DEFAULT_MAX_BUFFERED_RESPONSE_BYTES);
	}

	/**
	 * B3HttpClientConnectionFactory.
	 *
	 * @param httpClientConnectionManagerFactory HttpClientConnectionManagerFactory
	 * @param connectTimeoutSeconds int
	 */
	public B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory httpClientConnectionManagerFactory,
                                         int connectTimeoutSeconds) {
		this(httpClientConnectionManagerFactory, connectTimeoutSeconds, DEFAULT_MAX_BUFFERED_RESPONSE_BYTES);
	}

	/**
	 * B3HttpClientConnectionFactory.
	 *
	 * @param httpClientConnectionManagerFactory HttpClientConnectionManagerFactory
	 * @param connectTimeoutSeconds int
	 * @param maxBufferedResponseBytes largest response body that is read into memory so that the
	 *            connection can be released before the caller sees the response; bigger bodies are
	 *            left streaming and release when the caller finishes the stream. Must be positive —
	 *            a limit of zero would hold a socket open for every response nobody reads, which is
	 *            the defect this buffering exists to remove, so it is refused rather than accepted.
	 * @throws IllegalArgumentException if maxBufferedResponseBytes is not positive
	 */
	public B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory httpClientConnectionManagerFactory,
                                         int connectTimeoutSeconds,
                                         int maxBufferedResponseBytes) {
		if (maxBufferedResponseBytes <= 0) {
			throw new IllegalArgumentException("maxBufferedResponseBytes must be positive: " //$NON-NLS-1$
					+ maxBufferedResponseBytes);
		}
		this.httpClientConnectionManagerFactory=httpClientConnectionManagerFactory;
		this.connectTimeoutSeconds=connectTimeoutSeconds;
		this.maxBufferedResponseBytes=maxBufferedResponseBytes;
	}

	/**
	 *
	 * @param url url
	 *            a {@link java.net.URL} object.
	 * @return HttpConnection HttpConnection
	 * @throws IOException IOException
	 */
	@Override
	public HttpConnection create(URL url) throws IOException {
		return new B3HttpClientConnection(url.toString(), connectTimeoutSeconds, httpClientConnectionManagerFactory,
				maxBufferedResponseBytes);
	}

	/**
	 *
	 * @param url
	 *            a {@link java.net.URL} object.
	 * @param proxy
	 *            the proxy to be used
	 * @return HttpConnection HttpConnection
	 * @throws IOException IOException
	 */
	@Override
	public HttpConnection create(URL url, Proxy proxy)
			throws IOException {
		return new B3HttpClientConnection(url.toString(), connectTimeoutSeconds, proxy,
				httpClientConnectionManagerFactory, maxBufferedResponseBytes);
	}

	/**
	 * HttpClientConnectionManagerFactory.
	 */
	public interface HttpClientConnectionManagerFactory {
		/**
		 *
		 * @return HttpClientConnectionManager HttpClientConnectionManager.
		 */
		HttpClientConnectionManager getConnectionManager();
	}
}
