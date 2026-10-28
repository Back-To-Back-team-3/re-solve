package com.backtoback.problem.problem.domain;

/**
 * 본문·이미지 파일의 S3 연결 상태 (ERD 4.2.3 problem_body_assets.status, 4.2.4 problem_images.status).
 */
public enum ProblemAssetStatus {
    PENDING, LINKED, DISCARDED
}
