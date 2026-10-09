package com.esep.common.exception;

/**
 * Stable machine-readable error codes, returned as "code" in every error response.
 * Clients (the frontend) switch on the code, never on the human-readable "detail" text.
 * Codes may be added; existing ones are never renamed.
 */
public enum ErrorCode {
    // 400
    VALIDATION_FAILED,
    INVALID_REQUEST,
    MALFORMED_REQUEST,
    MISSING_PARAMETER,
    // 401 / 403
    UNAUTHORIZED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    // 404 / 405 / 415
    RESOURCE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    // 409
    CONFLICT,
    EMAIL_ALREADY_REGISTERED,
    DUPLICATE_ACCOUNT,
    CONCURRENT_MODIFICATION,
    // 422 business rules
    BUSINESS_RULE_VIOLATED,
    INSUFFICIENT_FUNDS,
    ACCOUNT_CLOSED,
    ACCOUNT_NOT_EMPTY,
    SAME_ACCOUNT_TRANSFER,
    CURRENCY_MISMATCH,
    UNSUPPORTED_CURRENCY,
    SYSTEM_ACCOUNT_OPERATION,
    INVALID_AMOUNT,
    IDEMPOTENCY_KEY_REUSED,
    // 429
    RATE_LIMITED,
    // 500
    INTERNAL_ERROR
}
