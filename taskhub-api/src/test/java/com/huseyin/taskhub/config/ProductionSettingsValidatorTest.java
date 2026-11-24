package com.huseyin.taskhub.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huseyin.taskhub.TaskhubApiApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Starts the real application the way {@code java -jar} does, with the prod profile. It must fail before any bean (or
 * database connection) is created, and name every missing environment variable.
 */
class ProductionSettingsValidatorTest {

    @BeforeAll
    static void requireCleanEnvironment() {
        for (String name : ProductionSettingsValidator.REQUIRED_ENV) {
            assumeTrue(System.getenv(name) == null, name + " is set in this shell; unset it to run this test");
        }
    }

    @Test
    void prodStartupWithoutSecretsFailsNamingEveryMissingVariable() {
        assertThatThrownBy(() -> TaskhubApiApplication.application().run("--spring.profiles.active=prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("DB_URL")
                .hasMessageContaining("DB_USERNAME")
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void prodStartupWithOnlyJwtSecretMissingNamesJustThatVariable() {
        assertThatThrownBy(() -> TaskhubApiApplication.application()
                        .run(
                                "--spring.profiles.active=prod",
                                "--DB_URL=jdbc:postgresql://db:5432/taskhub",
                                "--DB_USERNAME=taskhub",
                                "--DB_PASSWORD=secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageNotContaining("DB_URL");
    }
}
