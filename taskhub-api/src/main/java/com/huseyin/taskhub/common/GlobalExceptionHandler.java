package com.huseyin.taskhub.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place that turns exceptions into RFC 9457 {@code application/problem+json} responses (SPEC § Session
 * 1). Spring MVC's own exceptions (unknown route, wrong method, unreadable body, …) are handled by the
 * {@link ResponseEntityExceptionHandler} base class. Here we only add stable {@code type} URIs and the {@code errors}
 * array for validation failures.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomain(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(ex.type());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setType(ProblemTypes.INTERNAL);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        problem.setType(ProblemTypes.VALIDATION);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Don't echo the parser message: it can expose internal type names.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Malformed request body.");
        problem.setType(ProblemTypes.MALFORMED_REQUEST);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Last hook before the response is written, and the first point where Spring's own exceptions (e.g.
     * {@code NoResourceFoundException}) have a resolved ProblemDetail body. In {@code handleExceptionInternal} that
     * body is still null. Spring 7 leaves {@code type} null there, so we give the 404 our stable type.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && problem.getType() == null
                && status.value() == HttpStatus.NOT_FOUND.value()) {
            problem.setType(ProblemTypes.NOT_FOUND);
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
