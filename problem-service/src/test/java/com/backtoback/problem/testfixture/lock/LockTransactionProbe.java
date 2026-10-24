package com.backtoback.problem.testfixture.lock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 실제 DB 트랜잭션 잠금·롤백 통합 테스트에 사용하는 행이다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "lock_transaction_probe")
public class LockTransactionProbe {
    @Id
    private Long id;

    @Column(
        nullable = false,
        length = 200
    )
    private String payload;

    private LockTransactionProbe(Long id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    /**
     * 지정한 ID와 본문을 가진 DB 잠금 테스트 행을 만든다.
     *
     * @param id 테스트에서 고정한 행 ID
     * @param payload 저장할 테스트 본문
     * @return 저장할 테스트 행
     */
    public static LockTransactionProbe create(Long id, String payload) {
        return new LockTransactionProbe(id, payload);
    }
}
