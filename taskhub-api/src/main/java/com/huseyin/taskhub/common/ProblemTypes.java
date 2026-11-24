package com.huseyin.taskhub.common;

import java.net.URI;

/**
 * Stable RFC 9457 problem {@code type} identifiers. Clients may switch on these, so a value is never changed or
 * reused once published. Add new ones instead.
 */
public final class ProblemTypes {

    private static final String PREFIX = "urn:taskhub:problem:";

    public static final URI NOT_FOUND = URI.create(PREFIX + "not-found");
    public static final URI CONFLICT = URI.create(PREFIX + "conflict");
    public static final URI FORBIDDEN = URI.create(PREFIX + "forbidden");
    public static final URI UNPROCESSABLE = URI.create(PREFIX + "unprocessable");
    public static final URI VALIDATION = URI.create(PREFIX + "validation");
    public static final URI MALFORMED_REQUEST = URI.create(PREFIX + "malformed-request");
    public static final URI INTERNAL = URI.create(PREFIX + "internal");

    private ProblemTypes() {}
}
