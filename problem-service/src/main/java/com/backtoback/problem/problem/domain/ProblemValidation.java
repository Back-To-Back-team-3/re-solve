package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 고정 회차·언어·코드 파일의 검증 요청과 최종 반영 요약을 보관한다. */
@Entity
@Table(name = "problem_validations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemValidation extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        name = "problem_revision_id",
        nullable = false
    )
    private Long problemRevisionId;
    @Column(
        name = "requested_by",
        nullable = false
    )
    private Long requestedBy;
    @Column(
        nullable = false,
        length = 30
    )
    private String language;
    @Column(
        name = "code_asset_id",
        nullable = false
    )
    private Long codeAssetId;
    @Column(
        name = "trigger_type",
        nullable = false,
        length = 20
    )
    private String triggerType;
    @Column(
        name = "idempotency_key",
        length = 100
    )
    private String idempotencyKey;
    @Column(
        name = "request_hash",
        nullable = false,
        length = 64
    )
    private String requestHash;
    @Column(
        name = "result_status",
        length = 30
    )
    private String resultStatus;
    @Column(
        name = "runtime_key",
        length = 100
    )
    private String runtimeKey;
    @Column(
        name = "runner_version",
        length = 100
    )
    private String runnerVersion;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * 원래 접수 입력을 변경 불가능한 요청으로 구성한다.
     *
     * @param revisionId 고정 회차
     * @param authorId 작성 관리자
     * @param language 요청 언어
     * @param assetId 코드 파일
     * @param key 멱등키
     * @param hash 원래 요청 해시
     */
    private ProblemValidation(Long revisionId, Long authorId, String language, Long assetId, String key, String hash) {
        this.problemRevisionId = revisionId;
        this.requestedBy = authorId;
        this.language = language;
        this.codeAssetId = assetId;
        this.triggerType = "MANUAL";
        this.idempotencyKey = key;
        this.requestHash = hash;
    }

    /**
     * 작성 관리자 수동 검증 요청을 등록한다.
     *
     * @param revisionId 고정 회차
     * @param authorId 작성 관리자
     * @param language 요청 언어
     * @param assetId 코드 파일
     * @param key 멱등키
     * @param hash 원래 요청 해시
     * @return 결과 미반영 수동 요청
     */
    public static ProblemValidation request(
        Long revisionId,
        Long authorId,
        String language,
        Long assetId,
        String key,
        String hash
    ) {
        return new ProblemValidation(revisionId, authorId, language, assetId, key, hash);
    }

    /**
     * 최종 결과가 아직 반영되지 않았는지 판단한다.
     *
     * @return 결과 미반영 여부
     */
    public boolean isPending() {
        return resultStatus == null;
    }

    /**
     * 해당 회차·언어의 검증을 이미 통과했는지 판단한다.
     *
     * @return 통과 여부
     */
    public boolean hasPassed() {
        return "PASSED".equals(resultStatus);
    }

    /**
     * 최초 종결 결과를 반영하고 동일 결과를 재수신하면 기존 값을 유지한다.
     *
     * @param status 종결 상태
     * @param runtime 통과 환경
     * @param runner 생성기 버전
     * @param finished Judge 완료 시각
     * @return 최초 반영 여부
     * @throws IllegalArgumentException 종결 정보가 누락되거나 기존 결과와 상충하는 경우
     */
    public boolean complete(String status, String runtime, String runner, LocalDateTime finished) {
        if (status == null || finished == null || !Set.of("PASSED", "ERROR", "REJECTED").contains(status)
            || ("PASSED".equals(status)
                ? runtime == null || !runtime.matches("[a-f0-9]{64}") || runner == null || runner.isBlank()
                : runtime != null || runner != null)) {
            throw new IllegalArgumentException("검증 종결 정보 불일치");
        }
        if (!isPending()) {
            if (!resultStatus.equals(status) || !Objects.equals(runtimeKey, runtime)
                || !Objects.equals(runnerVersion, runner) || !finishedAt.equals(finished)) {
                throw new IllegalArgumentException("이미 반영한 검증 결과와 상충합니다.");
            }
            return false;
        }
        resultStatus = status;
        runtimeKey = runtime;
        runnerVersion = runner;
        finishedAt = finished;
        return true;
    }

}
