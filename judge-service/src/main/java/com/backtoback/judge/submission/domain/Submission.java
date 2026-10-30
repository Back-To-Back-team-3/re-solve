package com.backtoback.judge.submission.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.Assert;

import com.backtoback.common.jpa.entity.BaseTimeEntity;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.domain.JudgeVerdict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 제출 원본과 현재 채점 회차의 대표 상태를 저장한다. */
@Entity
@Table(name = "submissions")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission extends BaseTimeEntity {
    /** 제출을 접수한 서비스 문맥이다. */
    public enum Context {
        PRACTICE, EXAM, CONTEST, DIAGNOSIS
    }
    /** 제출 대표 상태이며 테스트 판정과 구분한다. */
    public enum Status {
        QUEUED, JUDGING, RETRY_WAITING, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        nullable = false,
        updatable = false
    )
    private long memberId;
    @Column(
        nullable = false,
        updatable = false
    )
    private long problemId;
    @Column(
        nullable = false,
        updatable = false
    )
    private long problemRevisionId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private Context context;
    @Column(updatable = false)
    private Long contextId;
    @Column(updatable = false)
    private Long sourceSubmissionId;
    @Column(length = 100)
    private String idempotencyKey;
    @Column(
        length = 64,
        columnDefinition = "CHAR(64)"
    )
    private String requestHash;
    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private String language;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "runtime_id")
    private ExecutionRuntime runtime;
    @Column(
        nullable = false,
        columnDefinition = "MEDIUMTEXT",
        updatable = false
    )
    private String sourceCode;
    @Column(
        nullable = false,
        updatable = false
    )
    private int codeSizeBytes;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
        nullable = false,
        length = 30
    )
    private Status status;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private JudgeVerdict verdict;
    @Column(nullable = false)
    private int judgeAttempt;
    private Integer passedCount;
    private Integer totalCount;
    private Integer maxTimeMs;
    private Integer maxMemoryKb;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String compileMessage;
    @Column(
        nullable = false,
        columnDefinition = "DATETIME(6)",
        updatable = false
    )
    private LocalDateTime submittedAt;
    @Column(columnDefinition = "DATETIME(6)")
    private LocalDateTime judgedAt;

    /**
     * 접수 원본을 대기 상태의 첫 채점 회차로 만든다.
     *
     * @param draft 외부 검증을 마친 제출 원본
     */
    private Submission(SubmissionDraft draft) {
        Assert.notNull(draft, "제출 원본은 필수입니다.");
        Assert
            .isTrue(
                draft.memberId() > 0 && draft.problemId() > 0 && draft.problemRevisionId() > 0,
                "제출자·문제·회차 ID는 양수여야 합니다."
            );
        Assert.notNull(draft.context(), "제출 문맥은 필수입니다.");
        Assert.notNull(draft.submittedAt(), "원본 접수 시각은 필수입니다.");
        Assert.hasText(draft.sourceCode(), "제출 코드는 필수입니다.");
        JudgeLanguage.fromCode(draft.language());
        codeSizeBytes = draft.sourceCode().getBytes(StandardCharsets.UTF_8).length;
        Assert.isTrue(codeSizeBytes <= 64 * 1024, "제출 코드는 UTF-8 64KiB 이하여야 합니다.");
        if (draft.context() == Context.PRACTICE) {
            Assert
                .isTrue(
                    draft.contextId() == null && draft.sourceSubmissionId() == null,
                    "일반 풀이는 외부 문맥·접수 ID를 가지지 않습니다."
                );
            Assert.hasText(draft.idempotencyKey(), "일반 풀이 멱등 키는 필수입니다.");
            Assert.isTrue(draft.idempotencyKey().matches("[\\x00-\\x7F]{1,100}"), "멱등 키는 1~100자 ASCII여야 합니다.");
            Assert
                .isTrue(
                    draft.requestHash() != null && draft.requestHash().matches("[0-9a-fA-F]{64}"),
                    "요청 해시는 SHA-256 16진수여야 합니다."
                );
        } else {
            Assert
                .isTrue(
                    draft.contextId() != null && draft.contextId() > 0 && draft.sourceSubmissionId() != null
                        && draft.sourceSubmissionId() > 0,
                    "외부 제출은 문맥·접수 ID가 필요합니다."
                );
            Assert
                .isTrue(draft.idempotencyKey() == null && draft.requestHash() == null, "외부 제출은 원본 접수 ID로 멱등성을 구분합니다.");
        }
        memberId = draft.memberId();
        problemId = draft.problemId();
        problemRevisionId = draft.problemRevisionId();
        context = draft.context();
        contextId = draft.contextId();
        sourceSubmissionId = draft.sourceSubmissionId();
        idempotencyKey = draft.idempotencyKey();
        requestHash = draft.requestHash();
        language = draft.language();
        sourceCode = draft.sourceCode();
        submittedAt = draft.submittedAt();
        status = Status.QUEUED;
        judgeAttempt = 1;
    }

    /**
     * 새 제출을 실행 환경 미확정 상태로 접수한다.
     *
     * @param draft 저장할 제출 원본
     * @return 채점 대기 상태의 제출
     * @throws JudgeException {@code UNSUPPORTED_LANGUAGE}: 지원하지 않는 언어인 경우
     * @throws IllegalArgumentException 원본 식별자·문맥·코드·멱등 정보가 잘못된 경우
     */
    public static Submission queued(SubmissionDraft draft) {
        return new Submission(draft);
    }

    /**
     * 제출 언어에 맞는 저장된 실행 환경을 한 번만 연결한다.
     * 호출자는 제출 행 잠금 또는 동등한 조건부 갱신으로 동시 확정을 직렬화해야 한다.
     *
     * @param selected 연결할 영속 실행 환경
     * @throws IllegalArgumentException 환경이 미저장 상태이거나 제출 언어가 다른 경우
     * @throws IllegalStateException 이미 다른 환경으로 확정한 경우
     */
    public void fixRuntime(ExecutionRuntime selected) {
        Assert.notNull(selected, "고정 실행 환경은 필수입니다.");
        Assert.notNull(selected.getId(), "저장한 실행 환경만 연결할 수 있습니다.");
        Assert.isTrue(language.equals(selected.getLanguage()), "제출 언어와 실행 환경 언어가 다릅니다.");
        if (runtime != null) {
            Assert.state(Objects.equals(runtime.getId(), selected.getId()), "확정한 실행 환경은 변경할 수 없습니다.");
            return;
        }
        runtime = selected;
    }
}
