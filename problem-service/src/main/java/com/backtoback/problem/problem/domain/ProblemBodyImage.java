package com.backtoback.problem.problem.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 본문 파일과 이미지의 연결(ERD 4.2.5 problem_body_images, {@code [IMMUTABLE]}).
 * 생성 시각만 있는 불변 테이블이라 {@code BaseTimeEntity}를 상속하지 않고, 기록 시점에 호출자가 시각을 전달한다
 * (공통 모듈 적용 가이드 §2.3 별도 시간 처리).
 */
@Getter
@Entity
@Table(
    name = "problem_body_images",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_problem_body_images_body_asset_image",
        columnNames = {
            "body_asset_id",
            "image_id"
        }
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemBodyImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "body_asset_id",
        nullable = false
    )
    private Long bodyAssetId;

    @Column(
        name = "image_id",
        nullable = false
    )
    private Long imageId;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    private ProblemBodyImage(Long bodyAssetId, Long imageId, LocalDateTime createdAt) {
        this.bodyAssetId = bodyAssetId;
        this.imageId = imageId;
        this.createdAt = createdAt;
    }

    /**
     * 본문 파일과 이미지의 연결 1건을 기록한다 (ERD 4.2.5).
     */
    public static ProblemBodyImage create(Long bodyAssetId, Long imageId, LocalDateTime createdAt) {
        return new ProblemBodyImage(bodyAssetId, imageId, createdAt);
    }
}
