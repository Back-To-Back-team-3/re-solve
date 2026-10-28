package com.backtoback.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(
    {
        GlobalExceptionHandler.class,
        JpaAuditingConfiguration.class
    }
)
public class IntegrationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationServiceApplication.class, args);
    }

}
