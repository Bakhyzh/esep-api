package com.esep.common.exception;

/** The request conflicts with the current state of a resource (e.g. a duplicate). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
