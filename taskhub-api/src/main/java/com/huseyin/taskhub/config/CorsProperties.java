package com.huseyin.taskhub.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Explicit CORS allow-list (SPEC § Session 2). Each entry is a full origin. A {@code *} wildcard is rejected at
 * startup.
 */
@Validated
@ConfigurationProperties("taskhub.cors")
public record CorsProperties(
        @NotNull @DefaultValue
        List<
                        @Pattern(
                                regexp = "^https?://[^*\\s/]+$",
                                message = "must be a full origin like https://app.example.com, never '*'")
                        String>
                allowedOrigins) {}
