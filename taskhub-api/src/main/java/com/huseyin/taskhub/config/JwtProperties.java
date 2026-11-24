package com.huseyin.taskhub.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings (SPEC § Session 2, Security rules). The secret signs HS256 tokens, so it needs at least 256 bits, and in
 * prod it only ever comes from the {@code JWT_SECRET} environment variable.
 */
@Validated
@ConfigurationProperties("taskhub.security.jwt")
public record JwtProperties(
        @NotBlank(message = "must be set via the JWT_SECRET environment variable")
        @Size(min = 32, message = "must be at least 32 bytes (256 bits) for HS256")
        String secret,

        @NotNull @DefaultValue("1h") Duration ttl) {

    @Override
    public String toString() {
        return "JwtProperties[secret=****, ttl=" + ttl + "]";
    }
}
