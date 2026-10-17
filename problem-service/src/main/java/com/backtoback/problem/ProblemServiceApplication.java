package com.backtoback.problem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Spring Security가 기본 사용자 계정을 만드는데, Gateway가 전달한 사용자 정보로 권한을 검사하니까 기본 계정이 필요 없어서 제외한다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ProblemServiceApplication {

    /**
     * 문제 서비스를 실행한다.
     *
     * @param args 애플리케이션 실행 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(ProblemServiceApplication.class, args);
    }

}
