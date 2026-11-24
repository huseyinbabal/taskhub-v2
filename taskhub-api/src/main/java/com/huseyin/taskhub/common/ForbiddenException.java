package com.huseyin.taskhub.common;

import org.springframework.http.HttpStatus;

/** The caller is authenticated but lacks access to an existing resource (403). */
public final class ForbiddenException extends DomainException {

    public ForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, detail);
    }
}
