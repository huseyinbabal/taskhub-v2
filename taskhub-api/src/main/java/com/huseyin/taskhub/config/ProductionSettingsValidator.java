package com.huseyin.taskhub.config;

import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fail-fast check for the prod profile (SPEC § Session 1, requirement 4). It runs as soon as the environment is
 * prepared, before any bean or database connection is created. Without it, Hibernate's eager startup fails first with
 * an unhelpful {@code 'url' must start with "jdbc"} and hides the real cause.
 *
 * <p>This only checks that each value is present. Format rules, such as the secret's 256-bit minimum, stay with the
 * typed properties ({@link JwtProperties}).
 */
public class ProductionSettingsValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final List<String> REQUIRED_ENV = List.of("JWT_SECRET", "DB_URL", "DB_USERNAME", "DB_PASSWORD");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> missing = REQUIRED_ENV.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The prod profile requires these environment variables, but they are not set: "
                            + String.join(", ", missing));
        }
    }
}
