package com.bigbrassband.common.apache;

import java.io.IOException;

/**
 * Raised by the body stream of a response other than 200 whose body {@link B3HttpClientConnection}
 * stopped reading before its end. The stream first hands over every byte that was kept and only
 * then throws this, so a caller that reached a normal end of stream has the whole body, and one
 * that gets this has a prefix of it.
 */
public class CutShortBodyException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Why the reading stopped.
	 */
	public enum Reason {
		/** The body was longer than {@link B3HttpClientConnection#MAX_NON_OK_BODY_BYTES} on the wire. */
		BYTE_LIMIT,
		/**
		 * The body, once decoded for {@link B3HttpClientConnection#getResponseMessage()}, was longer
		 * than {@link B3HttpClientConnection#MAX_NON_OK_BODY_BYTES}. Only that message names this
		 * reason: no body stream throws it, since the stream itself is not cut.
		 */
		DECODED_LIMIT,
		/** The body was still arriving when the time budget for reading it ran out. */
		TIME_BUDGET,
		/** Reading the body failed; the cause is that failure. */
		READ_FAILED,
		/** No finite read timeout was set, so nothing bounded the read and it was not started. */
		NOT_READ
	}

	private final Reason reason;

	private final int bytesKept;

	CutShortBodyException(int status, Reason reason, int bytesKept, IOException cause) {
		super(message(status, reason, bytesKept), cause);
		this.reason = reason;
		this.bytesKept = bytesKept;
	}

	/**
	 * @return why the reading stopped
	 */
	public Reason getReason() {
		return reason;
	}

	/**
	 * @return how many bytes of the body were kept: as they came off the wire, or for
	 *         {@link Reason#DECODED_LIMIT} as decoded
	 */
	public int getBytesKept() {
		return bytesKept;
	}

	static String message(int status, Reason reason, int bytesKept) {
		return "the body of this " + status + " response was cut short after " + bytesKept //$NON-NLS-1$ //$NON-NLS-2$
				+ " bytes: " + describe(reason); //$NON-NLS-1$
	}

	private static String describe(Reason reason) {
		switch (reason) {
		case BYTE_LIMIT:
			return "longer than " + B3HttpClientConnection.MAX_NON_OK_BODY_BYTES + " bytes"; //$NON-NLS-1$ //$NON-NLS-2$
		case DECODED_LIMIT:
			return "longer than " + B3HttpClientConnection.MAX_NON_OK_BODY_BYTES //$NON-NLS-1$
					+ " bytes once decoded"; //$NON-NLS-1$
		case TIME_BUDGET:
			return "still arriving when the time budget for reading it ran out"; //$NON-NLS-1$
		case READ_FAILED:
			return "reading it failed"; //$NON-NLS-1$
		case NOT_READ:
		default:
			return "not read, since no finite read timeout bounds the read"; //$NON-NLS-1$
		}
	}
}
