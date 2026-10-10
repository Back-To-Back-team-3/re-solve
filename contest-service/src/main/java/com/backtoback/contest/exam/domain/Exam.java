package com.backtoback.contest.exam.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backtoback.contest.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험 설정과 전체 진행 상태를 저장한다.
 * <p>Builder 또는 생성 팩토리로 초기 상태를 가진 객체를 만들고 Repository로 저장한다.
 * Builder는 create의 입력만 받으므로 식별자·상태·순번·감사 시간을 직접 지정하지 않는다.
 * 스터디와 개설자는 외부 서비스의 ID로만 참조하며 외부 DB에 FK를 만들지 않는다.
 * 생성 권한·입력 검증·상태 전이·이벤트 발행은 이 저장 모델에서 수행하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams): 컬럼·상태·설정 및 결과 순번·낙관적 락</li>
 * </ul>
 */
@Getter
@Entity
@Table(
    name = "exams",
    indexes = {
        @Index(
            name = "idx_exams_study_starts_at",
            columnList = "study_id, starts_at"
        ),
        @Index(
            name = "idx_exams_status_starts_at",
            columnList = "status, starts_at"
        ),
        @Index(
            name = "idx_exams_status_ends_at",
            columnList = "status, ends_at"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Exam extends BaseTimeEntity {

    // 저장 전에는 null이며 INSERT 시 DB가 식별자를 생성한다. nullable은 저장할 컬럼의 제약을 나타낸다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false)
    private Long studyId;

    @Column(nullable = false)
    private Long creatorId;

    @Column(
        nullable = false,
        length = 200
    )
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamMode mode;

    // 시간대 정보 없이 Asia/Seoul 기준의 시험 시작 시간을 저장한다.
    @Column(nullable = false)
    private LocalDateTime startsAt;

    // 예정 종료 시간이며 수동 종료 등으로 기록하는 실제 종료 시간 closedAt과 구분한다.
    @Column(nullable = false)
    private LocalDateTime endsAt;

    // FIXED에서는 null이며, WINDOW에서 참가자별 제한 시간을 분 단위로 나타낸다.
    @Column(nullable = true)
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30,
        columnDefinition = "VARCHAR(30)"
    )
    private ExamStatus status;

    // 시험 설정 변경의 이벤트 순서 값이다. 참가 등록으로 증가시키는 값이나 JPA version과 다르다.
    @Column(nullable = false)
    private Integer examRevision;

    // 문제별 배점의 합계(점)다. 생성 시 전달받은 값을 사용하며 이 모델에서 합계를 계산하지 않는다.
    @Column(
        nullable = false,
        precision = 6,
        scale = 2
    )
    private BigDecimal totalScore;

    // 실제 종료 전에는 null이며 예정 종료 시간인 endsAt과 구분한다.
    @Column(nullable = true)
    private LocalDateTime closedAt;

    // 잠정 결과 갱신·최초 확정·재확정의 순서 값이다. 현재 모델은 초기값 0만 설정한다.
    @Column(nullable = false)
    private Long resultRevision;

    // 최초 결과 확정 전에는 null이다.
    @Column(nullable = true)
    private LocalDateTime finalizedAt;

    // 취소되지 않은 시험은 null이며 취소 상태에서도 시험 행은 보존한다.
    @Column(nullable = true)
    private LocalDateTime canceledAt;

    // JPA UPDATE 경합을 검출하는 값이며 설정·결과의 업무 순번을 대신하지 않는다.
    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * 전달받은 설정을 저장 모델에 옮기고 생성 시 사용할 상태·순번을 초기화한다.
     * 식별자와 낙관적 락 버전은 저장 시 JPA가 관리한다.
     *
     * @param studyId 소속 스터디 ID
     * @param creatorId 개설자 회원 ID
     * @param title 시험 제목
     * @param mode 시험 진행 방식
     * @param startsAt 시작 시간
     * @param endsAt 예정 종료 시간
     * @param durationMinutes WINDOW 제한 시간(분), FIXED는 null
     * @param totalScore 시험 배점 합계
     */
    private Exam(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        BigDecimal totalScore
    ) {
        this.studyId = studyId;
        this.creatorId = creatorId;
        this.title = title;
        this.mode = mode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.durationMinutes = durationMinutes;
        this.status = ExamStatus.SCHEDULED;
        this.examRevision = 1;
        this.totalScore = totalScore;
        this.resultRevision = 0L;
    }

    /**
     * 저장 전 시험 객체를 만든다. DB 저장이나 외부 자원 조회는 수행하지 않는다.
     * Builder의 build도 이 메서드를 호출하므로 생성 방식과 관계없이 같은 초기값을 사용한다.
     * Builder에서 생략한 입력은 null로 전달되며, 이 메서드는 필수 입력을 자동 검증하지 않는다.
     * 생성 권한·자원 상태·시간 범위·배점 검증은 호출하는 서비스에서 처리해야 한다.
     *
     * @param studyId 시험을 개설할 스터디의 외부 참조 ID
     * @param creatorId 생성 권한을 확인한 개설자의 외부 참조 ID
     * @param title 저장할 시험 제목
     * @param mode FIXED 또는 WINDOW 진행 방식
     * @param startsAt Asia/Seoul 기준 시작 시간
     * @param endsAt Asia/Seoul 기준 예정 종료 시간
     * @param durationMinutes WINDOW 개인 제한 시간(분), FIXED에서는 null
     * @param totalScore 문제별 배점 합계
     * @return SCHEDULED 상태, 설정 순번 1, 결과 순번 0인 미저장 시험 객체
     */
    @Builder
    public static Exam create(
        Long studyId,
        Long creatorId,
        String title,
        ExamMode mode,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer durationMinutes,
        BigDecimal totalScore
    ) {
        return new Exam(studyId, creatorId, title, mode, startsAt, endsAt, durationMinutes, totalScore);
    }
}
