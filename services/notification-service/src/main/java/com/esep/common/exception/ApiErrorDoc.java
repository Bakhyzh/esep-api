package com.esep.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** OpenAPI description of the error body produced by GlobalExceptionHandler (documentation only). */
@Schema(name = "ApiError", description = "Every error response (RFC 9457 problem+json with a stable code)")
public record ApiErrorDoc(
        @Schema(example = "about:blank") String type,
        @Schema(example = "Business rule violated") String title,
        @Schema(example = "422") int status,
        @Schema(description = "Human-readable message, may change; do not parse it",
                example = "Insufficient funds on account 2") String detail,
        @Schema(example = "/api/transfers") String instance,
        @Schema(description = "Stable machine-readable code") ErrorCode code,
        Instant timestamp,
        @Schema(description = "Present only for VALIDATION_FAILED") List<GlobalExceptionHandler.FieldError> errors
) {
}
