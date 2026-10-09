package com.esep.ratelimit;

import lombok.Getter;

import java.time.Duration;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests, please try again later");
        this.retryAfter = retryAfter;
    }
}
