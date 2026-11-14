package com.backtoback.judge.run.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;

/** Run 실행과 하트비트의 수명을 관리하고 공통 스케줄러 잠금을 재사용한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(SchedulerLockConfiguration.class)
public class RunConfiguration {
    /** HTTP 요청의 30초 대기와 분리한 가상 스레드 실행기를 제공한다. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService runExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 실행 취소와 별도로 소유 토큰을 갱신하는 예약 실행기를 제공한다. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService runHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("run-heartbeat-", 0).factory());
    }
}
