package com.bigbrassband.common.apache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

/**
 * An {@link ErrInFetchAnswer} where JGit reports a remote's ERR line as a
 * {@link RemoteRepositoryException}.
 */
public class ErrInFetchAnswerRemoteRepositoryException
		extends RemoteRepositoryException implements ErrInFetchAnswer {
	private static final long serialVersionUID = 1L;

	private final String errText;

	private Map<String, ObjectId> wants = Collections.emptyMap();

	/**
	 * @param uri
	 *            the remote the request went to
	 * @param errText
	 *            the text of the ERR line, without its {@code ERR } prefix
	 */
	public ErrInFetchAnswerRemoteRepositoryException(URIish uri,
			String errText) {
		super(uri, errText);
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
