package com.esep.notification;

/** The message cannot be processed no matter how often we retry (malformed JSON, missing fields). */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
