package com.backtoback.problem.problem.domain;

import com.backtoback.problem.global.entity.BaseEntity;

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
 * 메타 정보만 관리한다. 본문 연결(링크) 전환은 해당 기능 Issue에서 엔티티 메서드로 추가한다.
 */
@Entity
@Table(
    name = "problem_images",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_images_object_key",
        columnNames = "object_key"
    ),
    indexes = {
        @Index(
            name = "idx_problem_images_status_created_at",
            columnList = "status, created_at"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemImage extends BaseEntity {
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

    private ProblemImage(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        String contentType,
        long sizeBytes
    ) {
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.objectVersion = objectVersion;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = ProblemAssetStatus.PENDING;
    }

    /**
     * 문제 본문에 아직 연결되지 않은({@code PENDING}) 이미지를 만든다. 본문 연결은 해당 기능 Issue에서 추가한다.
     */
    public static ProblemImage create(
        Long ownerId,
        String objectKey,
        String objectVersion,
        String sha256,
        String contentType,
        long sizeBytes
    ) {
        return new ProblemImage(ownerId, objectKey, objectVersion, sha256, contentType, sizeBytes);
    }
}
