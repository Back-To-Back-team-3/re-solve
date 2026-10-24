package com.backtoback.study;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;

// Gateway가 전달한 사용자 헤더로만 인증하므로 Boot가 만드는 기본 사용자 계정과 임시 비밀번호는 쓰지 않는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
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
