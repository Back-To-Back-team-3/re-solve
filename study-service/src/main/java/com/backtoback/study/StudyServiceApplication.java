package com.backtoback.study;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;

@SpringBootApplication
@Import(
    {
        GlobalExceptionHandler.class,
        JpaAuditingConfiguration.class,
        CorrelationIdFilter.class
    }
)
public class StudyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudyServiceApplication.class, args);
    }

}
