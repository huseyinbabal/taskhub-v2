package com.huseyin.taskhub.common;

import org.springframework.http.HttpStatus;

/** The request is well-formed but violates a business rule (422). */
public final class UnprocessableException extends DomainException {

    public UnprocessableException(String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.UNPROCESSABLE, detail);
    }
}
