package com.backtoback.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 사용자 인증 정보는 Gateway에서 전달받으므로 기본 사용자 계정 생성을 제외한다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class JudgeServiceApplication {

    /**
     * 채점 서비스를 실행한다.
     *
     * @param args 애플리케이션 실행 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(JudgeServiceApplication.class, args);
    }

}
