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
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.eclipse.jgit.transport.HttpAuthMethod;
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

	static {
		HttpAuthMethod.NTLM.requestConfigurer = (conn, username, password, domain) -> {
			if (conn instanceof B3HttpClientConnection) {
				B3HttpClientConnection b3HttpClientConnection = (B3HttpClientConnection) conn;
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
				credentialsProvider.setCredentials(AuthScope.ANY,
						new NTCredentials(username, password, null, domain));
				b3HttpClientConnection.setCredentialsProvider(credentialsProvider);
			}
		};
	}

	/**
	 * httpClientConnectionManagerFactory
	 */
	protected final HttpClientConnectionManagerFactory httpClientConnectionManagerFactory;
	private final int connectTimeoutSeconds;

	/**
	 * Constructor
	 */
	public B3HttpClientConnectionFactory() {
		this(null, 0);
	}

	/**
	 * Constructor
	 * @param httpClientConnectionManagerFactory stub
	 */
	public B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory httpClientConnectionManagerFactory) {
		this(httpClientConnectionManagerFactory, 0);
	}

	/**
	 * Constructor
	 * @param httpClientConnectionManagerFactory stub
	 * @param connectTimeoutSeconds stub
	 */
	public B3HttpClientConnectionFactory(HttpClientConnectionManagerFactory httpClientConnectionManagerFactory,
										 int connectTimeoutSeconds) {
		this.httpClientConnectionManagerFactory=httpClientConnectionManagerFactory;
		this.connectTimeoutSeconds=connectTimeoutSeconds;
	}

	/**
	 *
	 * @param url
	 *            a {@link java.net.URL} object.
	 * @return Returns http connection
	 * @throws IOException stub
	 */
	public HttpConnection create(URL url) throws IOException {
		return new B3HttpClientConnection(url.toString(), connectTimeoutSeconds, httpClientConnectionManagerFactory);
	}

	/**
	 *
	 * @param url
	 *            a {@link java.net.URL} object.
	 * @param proxy
	 *            the proxy to be used
	 * @return Returns http connection
	 * @throws IOException stub
	 */
	public HttpConnection create(URL url, Proxy proxy)
			throws IOException {
		return new B3HttpClientConnection(url.toString(), connectTimeoutSeconds, proxy, httpClientConnectionManagerFactory);
	}

	/**
	 * factory interface
	 */
	public interface HttpClientConnectionManagerFactory {
		/**
		 * @return Returns HttpClientConnectionManager
		 */
		HttpClientConnectionManager getConnectionManager();
	}
}
