package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.orm.jpa.JpaTransactionManager;

import com.backtoback.judge.judging.store.ValidationExecutionStore;

/** 관리자 검증 작업을 공통 DB 작업 스케줄러에 연결한다. */
public class ValidationScheduler extends JudgeWorkScheduler {
    /**
     * 검증 실행과 기한 초과 처리를 공통 스케줄러에 연결한다.
     *
     * @param worker 검증 실행기
     * @param store 검증 실행 저장소
     * @param executor 검증 전용 실행 스레드 풀
     * @param clock 공통 시각
     * @param transactionManager 기한 초과 처리 트랜잭션 관리자
     */
    public ValidationScheduler(
        ValidationWorker worker,
        ValidationExecutionStore store,
        Executor executor,
        Clock clock,
        JpaTransactionManager transactionManager
    ) {
        super(worker::runOnce, store::expireOne, executor, clock, transactionManager);
    }
}
