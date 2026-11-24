package com.huseyin.taskhub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the real {@code application*.yml} files for a given profile and binds only the typed properties, so we can
 * prove the fail-fast behaviour without starting the whole app or a database.
 */
class ApplicationPropertiesTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes = 256 bits

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class);

    @BeforeAll
    static void requireNoJwtSecretInEnvironment() {
        assumeTrue(System.getenv("JWT_SECRET") == null, "JWT_SECRET is set in this shell; unset it to run this test");
    }

    @Test
    void prodWithoutJwtSecretFailsAtStartupNamingTheVariable() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(fullMessage(context.getStartupFailure()))
                    .contains("taskhub.security.jwt.secret")
                    .contains("JWT_SECRET");
        });
    }

    @Test
    void prodWithTooShortJwtSecretFails() {
        runner.withPropertyValues("spring.profiles.active=prod", "JWT_SECRET=too-short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(fullMessage(context.getStartupFailure())).contains("256 bits");
                });
    }

    @Test
    void prodWithValidJwtSecretBindsWithOneHourDefaultTtl() {
        runner.withPropertyValues("spring.profiles.active=prod", "JWT_SECRET=" + VALID_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties jwt = context.getBean(JwtProperties.class);
                    assertThat(jwt.secret()).isEqualTo(VALID_SECRET);
                    assertThat(jwt.ttl()).isEqualTo(Duration.ofHours(1));
                });
    }

    @Test
    void devProfileStartsWithoutAnyEnvironmentVariables() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CorsProperties.class).allowedOrigins()).contains("http://localhost:3000");
        });
    }

    @Test
    void corsWildcardOriginIsRejected() {
        runner.withPropertyValues("spring.profiles.active=dev", "taskhub.cors.allowed-origins[0]=*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(fullMessage(context.getStartupFailure()))
                            .contains("allowedOrigins[0]")
                            .contains("never '*'");
                });
    }

    private static String fullMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            message.append(t.getMessage()).append('\n');
        }
        return message.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
    static class PropertiesConfig {}
}
