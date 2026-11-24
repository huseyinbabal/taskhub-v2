package com.huseyin.taskhub.common;

import org.springframework.http.HttpStatus;

/** The request conflicts with current state, e.g. a duplicate or stale version (409). */
public final class ConflictException extends DomainException {

    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, detail);
    }
}
