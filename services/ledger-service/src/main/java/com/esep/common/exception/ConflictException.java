package com.esep.common.exception;

/** The request conflicts with the current state of a resource (e.g. a duplicate). */
public class ConflictException extends RuntimeException {

    private final ErrorCode code;

    public ConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
