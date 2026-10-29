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
 * 상속한다. 본문 연결(링크) 전환, 업로드 결과·멱등 응답 기록은 해당 기능 Issue에서 엔티티 메서드로 추가한다.
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
     * 시각(시작·유효한 갱신 시각 + 24시간)이며 호출자가 전달한다. 본문 연결, 업로드 결과·멱등 응답 기록은 해당 기능
     * Issue에서 추가한다.
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
}
