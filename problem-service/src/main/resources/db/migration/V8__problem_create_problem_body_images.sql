CREATE TABLE problem_body_images (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '본문-이미지 연결 ID',
    body_asset_id BIGINT NOT NULL COMMENT '연결된 본문 파일',
    image_id BIGINT NOT NULL COMMENT '연결된 이미지 파일',
    created_at DATETIME(6) NOT NULL COMMENT '연결 생성 시각',
    PRIMARY KEY (id),
    CONSTRAINT fk_problem_body_images_body_asset FOREIGN KEY (body_asset_id) REFERENCES problem_body_assets (id),
    CONSTRAINT fk_problem_body_images_image FOREIGN KEY (image_id) REFERENCES problem_images (id),
    CONSTRAINT uk_problem_body_images_body_asset_image UNIQUE (body_asset_id, image_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='본문 파일과 이미지 파일의 연결을 불변으로 관리한다';
