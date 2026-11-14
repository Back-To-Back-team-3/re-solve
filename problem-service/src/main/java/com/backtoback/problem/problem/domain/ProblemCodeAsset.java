package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문제 검증 코드 파일 메타 정보 엔티티(ERD 4.2.16 problem_code_assets). 코드 원문은 S3 비공개 버킷에 두고 객체
 * 키·체크섬 등 메타 정보만 관리한다. 업로드 추적·상태 전이는 ERD 4.2.3 공통 규칙을 따르며, 동시 수정 충돌은 낙관적 락이
 * 아니라 파일 행 잠금으로 관리하므로 {@code BaseTimeEntity}만 상속한다. 업로드 추적과 문제 연결의 호출자는
 * 파일 행을 잠근다(ERD 4.2.3).
 */
@Entity
@Table(
    name = "problem_code_assets",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_code_assets_object_key",
        columnNames = "object_key"
    ),
    indexes = {
        @Index(
            name = "idx_problem_code_assets_cleanup",
            columnList = "status, upload_finished_at, id"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemCodeAsset extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "owner_id",
        nullable = false
    )
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private ProblemAssetStatus status;

    @Column(
        name = "object_key",
        nullable = false,
        length = 500
    )
    private String objectKey;

    @Column(
        name = "object_version",
        length = 200
    )
    private String objectVersion;

    @Column(
        nullable = false,
        length = 64
    )
    private String sha256;

    @Column(
        name = "size_bytes",
        nullable = false
    )
    private long sizeBytes;

    @Column(
        name = "upload_expires_at",
        nullable = false
    )
    private LocalDateTime uploadExpiresAt;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "upload_finished_at")
    private LocalDateTime uploadFinishedAt;

    @Column(
        name = "failure_code",
        length = 100
    )
    private String failureCode;

    /**
     * 문제 소속과 업로드 메타 정보를 고정한다.
     *
     * @param problemId 소속 문제
     * @param ownerId 작성 관리자
     * @param objectKey 불변 키
     * @param objectVersion 객체 버전
     * @param sha256 체크섬
     * @param sizeBytes 원문 크기
     * @param uploadExpiresAt 보호 만료 시각
     */
    private ProblemCodeAsset(
        Long problemId,
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        this.problemId = java.util.Objects.requireNonNull(problemId);
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.objectVersion = objectVersion;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.uploadExpiresAt = uploadExpiresAt;
        this.status = ProblemAssetStatus.PENDING;
    }

    /**
     * 문제 소속을 고정한 미참조({@code PENDING}) 검증 코드 파일을 만든다. {@code uploadExpiresAt}은 업로드 보호
     * 만료 시각(시작·유효한 갱신 시각 + 24시간)이며 호출자가 전달한다.
     *
     * @param problemId 소속 문제
     * @param ownerId 작성 관리자
     * @param objectKey 불변 키
     * @param objectVersion 객체 버전
     * @param sha256 체크섬
     * @param sizeBytes 원문 크기
     * @param uploadExpiresAt 보호 만료 시각
     * @return 업로드를 추적할 코드 파일
     */
    public static ProblemCodeAsset create(
        Long problemId,
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        return new ProblemCodeAsset(problemId, ownerId, objectKey, objectVersion, sha256, sizeBytes, uploadExpiresAt);
    }

    /**
     * 같은 파일·객체 키를 소유한 진행 중 작업만 보호 기간을 갱신한다(정책 및 상태 §4).
     *
     * @param expectedExpiry 갱신 전 확인한 만료 시각
     * @param now 파일 잠금 후 확인한 현재 시각
     * @throws IllegalStateException 작업이 종료됐거나 기존 만료값이 바뀌었거나 만료된 경우
     */
    public void renewUpload(LocalDateTime expectedExpiry, LocalDateTime now) {
        requireActiveUpload(expectedExpiry);
        if (now == null || !now.isBefore(uploadExpiresAt) || now.plusHours(24).isBefore(uploadExpiresAt)) {
            throw new IllegalStateException("업로드 보호 기간을 갱신할 수 없습니다.");
        }
        uploadExpiresAt = now.plusHours(24);
    }

    /**
     * 저장 결과·체크섬과 기존 쓰기·재시도 종료를 확인한 뒤 기록한다(ERD 4.2.3).
     *
     * @param expectedExpiry 쓰기 작업이 사용한 보호 만료 시각
     * @param now 저장과 종료를 확인한 시각
     * @param confirmedVersion 확인된 객체 버전. 버전이 없으면 null
     * @throws IllegalStateException 작업 상태나 만료값이 바뀌었거나 만료된 경우
     */
    public void completeUpload(LocalDateTime expectedExpiry, LocalDateTime now, String confirmedVersion) {
        requireActiveUpload(expectedExpiry);
        if (now == null || !now.isBefore(uploadExpiresAt)) {
            throw new IllegalStateException("만료된 업로드는 연결 가능한 상태로 완료할 수 없습니다.");
        }
        objectVersion = confirmedVersion;
        uploadedAt = now;
        uploadFinishedAt = now;
        failureCode = null;
    }

    /**
     * 쓰기 결과가 불명확하면 종료 시각을 기록하지 않고 보호 상태를 유지한다(정책 및 상태 §4).
     *
     * @param expectedExpiry 쓰기 작업이 사용한 보호 만료 시각
     * @param code 원문이나 인증 정보를 포함하지 않는 실패 분류
     * @throws IllegalStateException 작업 상태나 기존 만료값이 바뀐 경우
     * @throws IllegalArgumentException 실패 분류가 비었거나 100자를 초과한 경우
     */
    public void recordUnknownUpload(LocalDateTime expectedExpiry, String code) {
        requireActiveUpload(expectedExpiry);
        requireFailureCode(code);
        failureCode = code;
    }

    /**
     * 쓰기 종료와 실제 참조가 없음을 확인한 뒤 실패 또는 만료된 업로드를 폐기한다(ERD 4.2.3).
     * 호출자는 같은 파일 행 잠금 아래 실제 참조가 없고 기존 쓰기·재시도가 종료됐음을 확인해야 한다.
     *
     * @param expectedExpiry 쓰기 작업이 사용한 보호 만료 시각
     * @param finishedAt 쓰기 종료를 확인한 시각
     * @param code 원문이나 인증 정보를 포함하지 않는 실패 분류
     * @throws IllegalStateException 작업 상태나 만료값이 바뀌었거나 종료 시각이 없는 경우
     * @throws IllegalArgumentException 실패 분류가 비었거나 100자를 초과한 경우
     */
    public void discardFinishedUpload(LocalDateTime expectedExpiry, LocalDateTime finishedAt, String code) {
        requireActiveUpload(expectedExpiry);
        requireFailureCode(code);
        if (finishedAt == null) {
            throw new IllegalStateException("쓰기 종료 시각이 필요합니다.");
        }
        uploadFinishedAt = finishedAt;
        failureCode = code;
        status = ProblemAssetStatus.DISCARDED;
    }

    /**
     * 이전 작업이 상태나 보호 기간을 바꾼 경우 후속 처리를 막는다.
     *
     * @param expectedExpiry 원래 보호 만료 시각
     * @throws IllegalStateException 상태나 만료값이 달라진 경우
     */
    private void requireActiveUpload(LocalDateTime expectedExpiry) {
        if (status != ProblemAssetStatus.PENDING || uploadFinishedAt != null || expectedExpiry == null
            || !expectedExpiry.equals(uploadExpiresAt)) {
            throw new IllegalStateException("업로드 상태 또는 보호 만료 시각이 변경되었습니다.");
        }
    }

    /**
     * 실패 분류의 필수 조건과 길이를 확인한다.
     *
     * @param code 실패 분류
     * @throws IllegalArgumentException 실패 분류가 올바르지 않은 경우
     */
    private void requireFailureCode(String code) {
        if (code == null || code.isBlank() || code.length() > 100) {
            throw new IllegalArgumentException("업로드 실패 분류가 올바르지 않습니다.");
        }
    }

    /**
     * 업로드가 완료된 검증 코드 파일을 같은 문제의 회차에 연결한다(ERD 4.2.3).
     *
     * @param problemId 연결할 문제 ID
     * @param requestingAdminId 파일 소유 여부를 확인할 관리자 ID
     * @throws IllegalStateException 관리자·업로드·상태·문제 소속이 연결 조건에 맞지 않는 경우
     */
    public void linkToProblem(Long problemId, Long requestingAdminId) {
        if (problemId == null || requestingAdminId == null || !ownerId.equals(requestingAdminId)) {
            throw new IllegalStateException("검증 코드 파일 소유자 또는 문제 ID가 올바르지 않습니다.");
        }
        if (uploadedAt == null || uploadFinishedAt == null || uploadExpiresAt == null
            || !uploadedAt.isBefore(uploadExpiresAt) || !uploadFinishedAt.isBefore(uploadExpiresAt)) {
            throw new IllegalStateException("검증 코드 파일의 업로드 완료 상태가 올바르지 않습니다.");
        }
        if (status == ProblemAssetStatus.PENDING && problemId.equals(this.problemId)) {
            this.status = ProblemAssetStatus.LINKED;
            return;
        }
        if (status != ProblemAssetStatus.LINKED || !problemId.equals(this.problemId)) {
            throw new IllegalStateException("검증 코드 파일을 이 문제에 연결할 수 없습니다.");
        }
    }
}
