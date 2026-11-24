package com.huseyin.taskhub;

import com.huseyin.taskhub.config.ProductionSettingsValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskhubApiApplication {

    public static void main(String[] args) {
        application().run(args);
    }

    /** The application as {@code main} starts it, also used by startup tests. */
    public static SpringApplication application() {
        SpringApplication application = new SpringApplication(TaskhubApiApplication.class);
        application.addListeners(new ProductionSettingsValidator());
        return application;
    }
}
