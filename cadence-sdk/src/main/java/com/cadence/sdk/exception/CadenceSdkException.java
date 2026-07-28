package com.cadence.sdk.exception;

/**
 * Thrown only by explicitly "strict" SDK operations. The default evaluation path never throws:
 * a flag platform that can take down its callers has failed at its one job.
 */
public class CadenceSdkException extends RuntimeException {

    public CadenceSdkException(String message) {
        super(message);
    }

    public CadenceSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}
