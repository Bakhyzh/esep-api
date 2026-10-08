package com.esep.common.exception;

/** The request parameters are invalid in combination (e.g. from > to), even if each one is valid alone. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
