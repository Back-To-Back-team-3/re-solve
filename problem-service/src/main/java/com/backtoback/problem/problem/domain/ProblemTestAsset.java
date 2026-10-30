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
 * 문제 테스트 파일 메타 정보 엔티티(ERD 4.2.6 problem_test_assets). 테스트 내용은 S3 비공개 버킷에 두고 객체
 * 키·체크섬 등 메타 정보만 관리한다. 업로드 추적·상태 전이는 ERD 4.2.3 공통 규칙을 따르며, 동시 수정 충돌은 낙관적 락이
 * 아니라 파일 행 잠금으로 관리하므로 {@code BaseTimeEntity}만 상속한다. 문제 연결과 업로드 결과 기록은 해당 기능
 * Issue에서 엔티티 메서드로 추가한다.
 */
@Entity
@Table(
    name = "problem_test_assets",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_test_assets_object_key",
        columnNames = "object_key"
    ),
    indexes = {
        @Index(
            name = "idx_problem_test_assets_cleanup",
            columnList = "status, upload_finished_at, id"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemTestAsset extends BaseTimeEntity {
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

    private ProblemTestAsset(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.objectVersion = objectVersion;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.uploadExpiresAt = uploadExpiresAt;
        this.status = ProblemAssetStatus.PENDING;
    }

    /**
     * 문제에 아직 연결되지 않은({@code PENDING}) 테스트 파일을 만든다. {@code uploadExpiresAt}은 업로드 보호
     * 만료 시각(시작·유효한 갱신 시각 + 24시간)이며 호출자가 전달한다. 문제 연결과 업로드 결과 기록은 해당 기능 Issue에서
     * 추가한다.
     */
    public static ProblemTestAsset create(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        long sizeBytes,
        LocalDateTime uploadExpiresAt
    ) {
        return new ProblemTestAsset(ownerId, objectKey, objectVersion, sha256, sizeBytes, uploadExpiresAt);
    }
}
