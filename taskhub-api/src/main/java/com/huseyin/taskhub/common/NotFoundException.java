package com.huseyin.taskhub.common;

import org.springframework.http.HttpStatus;

/** The resource does not exist (404). */
public final class NotFoundException extends DomainException {

    public NotFoundException(String detail) {
        super(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, detail);
    }
}
