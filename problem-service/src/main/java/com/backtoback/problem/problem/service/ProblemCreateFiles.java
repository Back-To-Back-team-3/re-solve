package com.backtoback.problem.problem.service;

import java.util.List;

/** 별도 트랜잭션에서 업로드 완료한 본문·테스트 파일과 내용 메타 정보. */
public record ProblemCreateFiles(Long bodyId, List<TestFile> tests) {
    /** 요청 순서에 대응하는 업로드 완료 테스트 파일. */
    public record TestFile(Long id, String sha256, long sizeBytes) {
    }
}
