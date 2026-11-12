package com.backtoback.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.config.EventConsumerConfiguration;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(EventConsumerConfiguration.class)
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }

}
