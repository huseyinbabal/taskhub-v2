package com.huseyin.taskhub.common;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Base class for expected business failures. {@link GlobalExceptionHandler} renders them as ProblemDetail. The message
 * becomes the client-visible {@code detail}, so it must never contain secrets or internals.
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, ConflictException, ForbiddenException, UnprocessableException {

    private final HttpStatus status;
    private final URI type;

    protected DomainException(HttpStatus status, URI type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }
}
