package com.esep.common.exception;

/** The request is valid, but violates a domain rule (e.g. closing an account with money on it). */
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode code;

    public BusinessRuleException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
