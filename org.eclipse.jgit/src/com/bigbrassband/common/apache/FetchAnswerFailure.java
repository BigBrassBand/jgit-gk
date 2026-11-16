package com.bigbrassband.common.apache;

import java.util.Map;

import org.eclipse.jgit.lib.ObjectId;

/**
 * A failure the remote sent in answer to a fetch request, carrying the wants of
 * that request, so that a caller can tell which state of the remote it was
 * about.
 */
public interface FetchAnswerFailure {

	/**
	 * @return the wants the request sent, in the order sent: the name of each
	 *         wanted Ref (one per object; the hex id for a want by object id) to
	 *         the id written on the wire; empty when the failure arrived before
	 *         they were known
	 */
	Map<String, ObjectId> getWants();

	/**
	 * Set by the fetch connection as the failure leaves it.
	 *
	 * @param wants
	 *            the wants the request sent, ref name to object id
	 */
	void setWants(Map<String, ObjectId> wants);
}
