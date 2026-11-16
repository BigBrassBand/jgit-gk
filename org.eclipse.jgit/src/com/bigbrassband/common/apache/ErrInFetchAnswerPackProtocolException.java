package com.bigbrassband.common.apache;

import org.eclipse.jgit.errors.PackProtocolException;

/**
 * An {@link ErrInFetchAnswer} where JGit reports a remote's ERR line as a
 * {@link PackProtocolException}.
 */
public class ErrInFetchAnswerPackProtocolException extends PackProtocolException
		implements ErrInFetchAnswer {
	private static final long serialVersionUID = 1L;

	private final String errText;

	/**
	 * @param message
	 *            the message JGit gives this failure
	 * @param errText
	 *            the text of the ERR line, without its {@code ERR } prefix
	 */
	public ErrInFetchAnswerPackProtocolException(String message,
			String errText) {
		super(message);
		this.errText = errText;
	}

	@Override
	public String getErrText() {
		return errText;
	}
}
