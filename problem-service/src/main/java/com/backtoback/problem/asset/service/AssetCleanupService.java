package com.backtoback.problem.asset.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.problem.config.ProblemImageStorageProperties;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 선점 커밋과 S3 삭제, 최종 DB 정리를 분리한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetCleanupService {
    private final AssetCleanupStore store;
    private final AssetObjectDeleter objects;
    private final ProblemImageStorageProperties storage;
    private final MeterRegistry metrics;

    /**
     * 한 파일만 처리하고 실패 시 DELETING을 유지한다.
     * 종료 미확인은 만료·객체 존재 여부로 추정하지 않고 보존한다.
     *
     * @param candidate 파일 후보
     * @param budget 전체 정리 회차의 실행 예산
     * @return 처리 결과
     * @throws RuntimeException 선점·S3·DB 정리 실패
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String clean(AssetCleanupStore.Candidate candidate, AssetCleanupBudget budget) {
        budget.requireDatabaseTime();
        if (!store.recordAttempt(candidate)) {
            return "skipped";
        }
        budget.requireDatabaseTime();
        var claim = store.claim(candidate);
        if (claim.file() == null) {
            if ("unconfirmed".equals(claim.outcome())) {
                log.warn("업로드 종료 미확인 파일 보존: kind={}, id={}", candidate.kind(), candidate.id());
            }
            return claim.outcome();
        }
        try {
            objects.delete(storage.bucket(), claim.file().key(), claim.file().version(), budget);
        } catch (RuntimeException failure) {
            metrics.counter("problem.asset.cleanup.s3.failures").increment();
            throw failure;
        }
        budget.requireDatabaseTime();
        if (store.complete(claim.file())) {
            metrics.counter("problem.asset.cleanup.deleted.rows").increment();
            return "deleted";
        }
        return "skipped";
    }
}
