package com.esep.common.exception;

/** The request is valid, but violates a domain rule (e.g. closing an account with money on it). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
