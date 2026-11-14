package com.bigbrassband.common.apache;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.URIish;

/**
 * A smart HTTP service request (upload-pack or receive-pack) answered with a
 * status JGit has no handling of its own for: anything but 200, 401, 403, 404
 * and a redirect it follows. The message is JGit's own for this failure; the
 * status is carried as a number so that a caller need not parse it.
 */
public class HttpStatusTransportException extends TransportException {
	private static final long serialVersionUID = 1L;

	private final int status;

	/**
	 * @param uri
	 *            the remote the request went to
	 * @param status
	 *            the HTTP status of the response
	 * @param message
	 *            the message JGit gives this failure
	 */
	public HttpStatusTransportException(URIish uri, int status, String message) {
		super(uri, message);
		this.status = status;
	}

	/**
	 * @return the HTTP status of the response
	 */
	public int getStatus() {
		return status;
	}
}
