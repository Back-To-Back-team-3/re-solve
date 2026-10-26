package com.backtoback.problem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** 문제 서비스를 기동하며 Gateway 인증을 사용하므로 기본 사용자 계정 생성을 제외한다. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ProblemServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProblemServiceApplication.class, args);
    }

}
