package com.backtoback.problem.testfixture.lock;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** 테스트용 DB 행을 저장·잠금하고 JPA 잠금 예외 변환을 확인한다. */
@Repository
public class LockTransactionProbeRepository {
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 테스트 행을 저장소에 반영해 현재 트랜잭션에서 INSERT를 실행한다.
     *
     * @param probe 저장할 잠금 테스트 행
     */
    public void saveAndFlush(LockTransactionProbe probe) {
        entityManager.persist(probe);
        entityManager.flush();
    }

    /**
     * 지정한 테스트 행을 쓰기 잠금으로 조회한다.
     *
     * @param id 잠글 행 ID
     * @return 현재 트랜잭션이 쓰기 잠금을 보유한 행
     */
    public LockTransactionProbe findForUpdate(long id) {
        return entityManager.find(LockTransactionProbe.class, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
