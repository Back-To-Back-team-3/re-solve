package com.backtoback.problem.asset.domain;

/** 정리 대상 테이블과 실제 참조 경로를 제한한다. */
public enum AssetKind {
    BODY(
        "problem_body_assets",
        "SELECT id FROM problem_revisions WHERE body_asset_id=? LIMIT 1",
        "SELECT id FROM problem_body_images WHERE body_asset_id=? LIMIT 1"),
    TEST("problem_test_assets", "SELECT id FROM problem_revision_tests WHERE test_asset_id=? LIMIT 1", null),
    IMAGE("problem_images", "SELECT id FROM problem_body_images WHERE image_id=? LIMIT 1", null),
    CODE("problem_code_assets", "SELECT id FROM problem_validations WHERE code_asset_id=? LIMIT 1", null);

    private final String table;
    private final String reference;
    private final String additionalReference;

    /**
     * 고정된 파일 종류와 참조 경로를 보관한다.
     *
     * @param table 파일 테이블
     * @param reference 실제 참조
     * @param additionalReference 본문 이미지 연결 참조
     */
    AssetKind(String table, String reference, String additionalReference) {
        this.table = table;
        this.reference = reference;
        this.additionalReference = additionalReference;
    }

    /**
     * 고정 파일 테이블을 반환한다.
     *
     * @return 테이블
     */
    public String table() {
        return table;
    }

    /**
     * 주요 실제 참조 조회를 반환한다.
     *
     * @return 참조 SQL
     */
    public String reference() {
        return reference;
    }

    /**
     * 추가 실제 참조 조회를 반환한다.
     *
     * @return 추가 SQL 또는 null
     */
    public String additionalReference() {
        return additionalReference;
    }
}
