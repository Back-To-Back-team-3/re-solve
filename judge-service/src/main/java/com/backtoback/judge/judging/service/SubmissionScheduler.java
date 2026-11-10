package com.backtoback.judge.judging.service;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.judge.judging.store.SubmissionExecutionStore;

/** 제출 작업의 실행과 임대 회수를 공통 작업 스케줄러에 연결한다. */
public class SubmissionScheduler extends JudgeWorkScheduler {
    private final SubmissionExecutionStore store;

    /**
     * 제출 실행·기한 초과 종결·임대 회수를 연결한다.
     *
     * @param worker 제출 실행기
     * @param store 제출 실행 저장소
     * @param executor 제출 전용 실행 스레드 풀
     * @param clock 공통 시각
     * @param transactionManager 회수 한 건마다 트랜잭션을 시작할 관리자
     */
    public SubmissionScheduler(
        SubmissionWorker worker,
        SubmissionExecutionStore store,
        Executor executor,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        super(worker::runOnce, store::recoverOne, executor, clock, transactionManager);
        this.store = store;
    }

    /** 인스턴스마다 같은 회수 루프를 반복하지 않도록 DB 스케줄러 잠금을 획득한다. (스케줄러 §3.2) */
    @Override
    @Scheduled(
        fixedDelay = 30000,
        scheduler = "judgeValidationScheduler"
    )
    @SchedulerLock(
        name = "JudgeLeaseRecoveryScheduler",
        lockAtMostFor = "25s"
    )
    public void expire() {
        super.expire();
    }

    /** 임대 만료와 독립적으로 Judge0 생성 응답 유실을 회수한다. (스케줄러 §3.3) */
    @Scheduled(
        fixedDelay = 30000,
        scheduler = "judgeValidationScheduler"
    )
    @SchedulerLock(
        name = "JudgeTokenRecoveryScheduler",
        lockAtMostFor = "25s"
    )
    public void recoverUnknown() {
        recoverBatch(store::recoverUnknownOne);
    }
}
