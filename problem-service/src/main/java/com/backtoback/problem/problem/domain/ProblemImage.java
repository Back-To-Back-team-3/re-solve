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
 * 문제 본문에 쓰이는 이미지 파일 메타 정보 엔티티(ERD 4.2.4 problem_images). 이미지 내용은 S3에 두고 객체 키·체크섬 등
 * 메타 정보만 관리한다. 동시 수정 충돌은 낙관적 락이 아니라 파일 행 잠금으로 관리하므로 {@code BaseTimeEntity}만
 * 상속한다. 업로드 결과·멱등 응답은 업로드 기능이 기록하고, 본문 연결은 저장 서비스가 파일 잠금 아래 전이한다.
 */
@Entity
@Table(
    name = "problem_images",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_problem_images_object_key",
            columnNames = "object_key"
        ),
        @UniqueConstraint(
            name = "uk_problem_images_owner_idempotency_key",
            columnNames = {
                "owner_id",
                "idempotency_key"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_problem_images_status_created_at",
            columnList = "status, created_at"
        ),
        @Index(
            name = "idx_problem_images_cleanup",
            columnList = "status, upload_finished_at, id"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemImage extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "problem_id")
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
        name = "idempotency_key",
        length = 100
    )
    private String idempotencyKey;

    @Column(name = "idempotency_expires_at")
    private LocalDateTime idempotencyExpiresAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(
        name = "response_json",
        columnDefinition = "json"
    )
    private String responseJson;

    @Column(
        name = "content_type",
        nullable = false,
        length = 100
    )
    private String contentType;

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

    private ProblemImage(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        String contentType,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.objectVersion = objectVersion;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadExpiresAt = uploadExpiresAt;
        this.status = ProblemAssetStatus.PENDING;
    }

    /**
     * 문제 본문에 아직 연결되지 않은({@code PENDING}) 이미지를 만든다. {@code uploadExpiresAt}은 업로드 보호 만료
     * 시각(시작·유효한 갱신 시각 + 24시간)이며 호출자가 전달한다.
     */
    public static ProblemImage create(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        String contentType,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        return new ProblemImage(ownerId, objectKey, objectVersion, sha256, contentType, sizeBytes, uploadExpiresAt);
    }

    /**
     * 업로드가 완료된 이미지를 문제에 연결한다. 같은 문제에서 이미 연결한 이미지는 새 본문에서도 재사용할 수 있다
     * (ERD 4.2.3).
     *
     * @param problemId 연결할 문제 ID
     * @param requestingAdminId 요청 관리자 ID
     * @throws IllegalStateException 소유자·문제·파일 상태 또는 업로드 완료 조건이 맞지 않는 경우
     */
    public void linkToProblem(Long problemId, Long requestingAdminId) {
        if (problemId == null || requestingAdminId == null || !ownerId.equals(requestingAdminId)) {
            throw new IllegalStateException("이미지 소유자 또는 문제 ID가 올바르지 않습니다.");
        }
        if (uploadedAt == null || uploadFinishedAt == null || uploadExpiresAt == null
            || !uploadedAt.isBefore(uploadExpiresAt) || !uploadFinishedAt.isBefore(uploadExpiresAt)) {
            throw new IllegalStateException("이미지의 업로드 완료 상태가 올바르지 않습니다.");
        }
        if (status == ProblemAssetStatus.PENDING && this.problemId == null) {
            this.problemId = problemId;
            this.status = ProblemAssetStatus.LINKED;
            return;
        }
        if (status != ProblemAssetStatus.LINKED || !problemId.equals(this.problemId)) {
            throw new IllegalStateException("이미지를 이 문제에 연결할 수 없습니다.");
        }
    }

}
