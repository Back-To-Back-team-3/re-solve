package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.util.concurrent.Executor;

import com.backtoback.judge.judging.store.ValidationExecutionStore;

/** 관리자 검증 작업을 공통 DB 작업 스케줄러에 연결한다. */
public class ValidationScheduler extends JudgeWorkScheduler {
    public ValidationScheduler(
        ValidationWorker worker,
        ValidationExecutionStore store,
        Executor executor,
        Clock clock
    ) {
        super(worker::runOnce, store::expireOne, executor, clock);
    }
}
