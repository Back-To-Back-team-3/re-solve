package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.backtoback.contest.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원의 시험 참가와 개인 마감·결과 정보를 저장한다.
 * Builder는 create의 입력만 받으며 참가 상태·순번·점수·생성 컬럼은 직접 지정하지 않는다.
 * <p>참가 객체를 생성한 뒤 소속 시험과 별도로 저장한다. 회원은 외부 ID로만 참조하며
 * 시험 관계에는 cascade 저장·삭제를 사용하지 않는다. 취소된 참가 행도 보존하고 재신청은 새 행으로 표현한다.
 * 참가 자격 검증과 상태 전이·이력·Outbox 기록은 후속 서비스 구현의 책임이며 이 클래스에는 구현하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.3 시험 참가자 (exam_participants): 참가 상태·개인 마감·결과·취소 후 재신청 제약</li>
 * </ul>
 */
@Getter
@Entity
@Table(
    name = "exam_participants",
    indexes = @Index(
        name = "idx_exam_participants_member_status",
        columnList = "member_id, status"
    ),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_exam_participants_exam_active_member",
        columnNames = {
            "exam_id",
            "active_member_id"
        }
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamParticipant extends BaseTimeEntity {

    // 저장 전에는 null이며 INSERT 시 DB가 식별자를 생성한다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "exam_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_exam_participants_exam")
    )
    private Exam exam;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamParticipantStatus status;

    // DB가 CANCELED는 null, 그 외 상태는 memberId로 계산한다. 시험과의 유일 제약으로 취소되지 않은 참가 1건만 허용한다.
    // INSERT·UPDATE에서 직접 쓰지 않으며 Hibernate는 DB에서 계산한 값을 다시 읽는다.
    @Generated(
        event = {
            EventType.INSERT,
            EventType.UPDATE
        }
    )
    @Column(
        nullable = true,
        insertable = false,
        updatable = false
    )
    private Long activeMemberId;

    // 참가 상태 전이의 업무 순번이다. 향후 상태·이력·Outbox와 같은 트랜잭션에서 증가시키며 JPA version과 구분한다.
    @Column(nullable = false)
    private Long participantRevision;

    @Column(nullable = false)
    private LocalDateTime registeredAt;

    // 최초 입장 전에는 null이다. 참가 등록만 한 회원과 실제 입장한 회원을 구분하는 기준이다.
    @Column(nullable = true)
    private LocalDateTime enteredAt;

    // FIXED는 시험의 endsAt을 전달받는다. 이 모델이 개인 마감을 계산하거나 수동 종료에 맞춰 갱신하지는 않는다.
    @Column(nullable = false)
    private LocalDateTime personalEndsAt;

    // 참가 종료 시간이 기록되기 전에는 null이다.
    @Column(nullable = true)
    private LocalDateTime finishedAt;

    // 문제별 획득 점수의 재계산 합계(점)다. 초기값은 0이며 실제 집계는 후속 서비스에서 수행한다.
    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal totalScore;

    // 유효 제출이 없으면 null이며, 결과 집계 시 동점 정렬에 사용하는 시간이다.
    @Column(nullable = true)
    private LocalDateTime lastValidSubmittedAt;

    // 자동 제출 완료 전에는 null이다. 후속 서비스는 제출 생성·FINISHED 전이와 같은 트랜잭션에서 기록해야 한다.
    @Column(nullable = true)
    private LocalDateTime autoSubmittedAt;

    // 순위 포함 여부다. 신규 참가자는 제출이 없으므로 false로 시작한다.
    @Column(
        name = "is_ranked",
        nullable = false
    )
    private boolean ranked;

    // 확정 순위가 없으면 null이다. 미제출자의 순위를 임의로 생성하지 않는다.
    @Column(nullable = true)
    private Integer finalRank;

    // 참가자 결과 UPDATE의 경합을 검출한다. 참가 상태의 업무 순번을 대신하지 않는다.
    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * 참가 관계·시간을 옮기고 등록 상태·상태 순번·초기 점수를 설정한다.
     * 미입장·미제출 필드와 확정 순위는 null, 순위 포함 여부는 false로 남긴다.
     *
     * @param exam 소속 시험
     * @param memberId 참가 회원 ID
     * @param registeredAt 참가 등록 시간
     * @param personalEndsAt 개인 마감 시간
     */
    private ExamParticipant(Exam exam, Long memberId, LocalDateTime registeredAt, LocalDateTime personalEndsAt) {
        this.exam = exam;
        this.memberId = memberId;
        this.status = ExamParticipantStatus.REGISTERED;
        this.participantRevision = 1L;
        this.registeredAt = registeredAt;
        this.personalEndsAt = personalEndsAt;
        this.totalScore = BigDecimal.ZERO;
    }

    /**
     * 신규 참가의 미저장 객체를 만든다. 회원 조회나 DB 저장은 수행하지 않는다.
     * Builder의 build도 이 메서드를 호출하므로 등록 상태와 초기 점수는 항상 같은 규칙으로 설정된다.
     * Builder에서 생략한 입력은 null로 전달되며, 이 메서드는 필수 입력을 자동 검증하지 않는다.
     * 참가 자격·등록 가능 상태·중복 여부와 개인 마감 계산은 호출하는 서비스에서 처리한다.
     * 취소되지 않은 중복 참가는 저장 시 DB 유일 제약으로도 차단된다.
     *
     * @param exam 먼저 저장할 소속 시험
     * @param memberId 참가 자격을 확인한 회원의 외부 참조 ID
     * @param registeredAt Asia/Seoul 기준 참가 등록 시간
     * @param personalEndsAt 서비스에서 결정한 Asia/Seoul 기준 개인 마감 시간
     * @return REGISTERED 상태, 참가 순번 1, 점수 0인 미저장 참가 객체
     */
    @Builder
    public static ExamParticipant create(
        Exam exam,
        Long memberId,
        LocalDateTime registeredAt,
        LocalDateTime personalEndsAt
    ) {
        return new ExamParticipant(exam, memberId, registeredAt, personalEndsAt);
    }
}
