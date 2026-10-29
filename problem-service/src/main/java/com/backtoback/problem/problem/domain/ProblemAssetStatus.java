package com.backtoback.problem.problem.domain;

/**
 * 본문·이미지 파일의 S3 연결 상태 (ERD 4.2.3 problem_body_assets.status, 4.2.4 problem_images.status).
 * {@code DELETING}은 삭제를 선점해 신규 연결·업로드를 금지한 상태다.
 */
public enum ProblemAssetStatus {
    PENDING, LINKED, DISCARDED, DELETING
}
