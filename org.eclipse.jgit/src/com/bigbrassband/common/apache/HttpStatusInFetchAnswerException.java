package com.bigbrassband.common.apache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

/**
 * An {@link HttpStatusTransportException} for the HTTP request that carries a
 * fetch's wants and filter. The protocol v2 {@code ls-refs} listing and a
 * receive-pack request throw the parent type.
 */
public class HttpStatusInFetchAnswerException
		extends HttpStatusTransportException implements FetchAnswerFailure {
	private static final long serialVersionUID = 1L;

	private Map<String, ObjectId> wants = Collections.emptyMap();

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

	@Override
	public Map<String, ObjectId> getWants() {
		return wants;
	}

	@Override
	public void setWants(Map<String, ObjectId> wants) {
		this.wants = Collections.unmodifiableMap(new LinkedHashMap<>(wants));
	}
}
