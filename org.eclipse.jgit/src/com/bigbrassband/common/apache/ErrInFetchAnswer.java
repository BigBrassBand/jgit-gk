package com.bigbrassband.common.apache;

/**
 * An ERR line the remote sent in answer to a fetch request: after the request's
 * {@code done}, or where an acknowledgment was due. An ERR sent during the ref
 * advertisement, before any request, is not one. The exception keeps the type
 * and the message JGit gives the same failure.
 */
public interface ErrInFetchAnswer extends FetchAnswerFailure {

	/**
	 * @return the text of the ERR line, without its {@code ERR } prefix
	 */
	String getErrText();
}
