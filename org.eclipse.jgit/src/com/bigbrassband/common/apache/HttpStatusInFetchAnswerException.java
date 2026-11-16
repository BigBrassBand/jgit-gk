package com.bigbrassband.common.apache;

import org.eclipse.jgit.transport.URIish;

/**
 * An {@link HttpStatusTransportException} for the HTTP request that carries a
 * fetch's wants and filter. The protocol v2 {@code ls-refs} listing and a
 * receive-pack request throw the parent type.
 */
public class HttpStatusInFetchAnswerException
		extends HttpStatusTransportException {
	private static final long serialVersionUID = 1L;

	/**
	 * @param uri
	 *            the remote the request went to
	 * @param status
	 *            the HTTP status of the response
	 * @param message
	 *            the message JGit gives this failure
	 */
	public HttpStatusInFetchAnswerException(URIish uri, int status,
			String message) {
		super(uri, status, message);
	}
}
