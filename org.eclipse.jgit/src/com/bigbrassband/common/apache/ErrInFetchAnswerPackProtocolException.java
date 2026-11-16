package com.bigbrassband.common.apache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.ObjectId;

/**
 * An {@link ErrInFetchAnswer} where JGit reports a remote's ERR line as a
 * {@link PackProtocolException}.
 */
public class ErrInFetchAnswerPackProtocolException extends PackProtocolException
		implements ErrInFetchAnswer {
	private static final long serialVersionUID = 1L;

	private final String errText;

	private Map<String, ObjectId> wants = Collections.emptyMap();

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

	@Override
	public Map<String, ObjectId> getWants() {
		return wants;
	}

	@Override
	public void setWants(Map<String, ObjectId> wants) {
		this.wants = Collections.unmodifiableMap(new LinkedHashMap<>(wants));
	}
}
