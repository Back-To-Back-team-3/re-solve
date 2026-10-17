package com.backtoback.contest.exam.client;

import java.util.List;
import java.util.Map;

/**
 * 시험 생성 시 공개 회차를 고정하고, 입장 후 지정 회차의 본문을 읽는 문제 서비스 경계다.
 * 최신 회차로 치환하거나 숨김 테스트·정답 코드를 가져오는 기능은 제공하지 않는다.
 * <p>기준 문서: API 명세서 v1.1 / §2.6.5 문제 선택용 요약 조회, §2.6.3 고정 회차 본문 조회,
 * §2.3.0 문제·테스트 공통 데이터 형식: 회차·공개 본문·실행 예제 계약.
 */
public interface ProblemClient {
    /**
     * EXAM 목적의 GENERAL·PUBLIC 문제 선택 요약을 조회한다.
     * 후속 HTTP 구현은 purpose=EXAM과 ids를 함께 보내고 가용성을 확인해야 한다.
     *
     * @param problemIds 생성 요청의 중복 없는 문제 ID
     * @return 가용 여부와 현재 공개 회차를 포함한 요약
     */
    List<Summary> getExamSummaries(List<String> problemIds);

    /**
     * 확인된 시험 문맥의 고정 회차 본문을 조회한다.
     * 후속 HTTP 구현은 X-Access-Context: EXAM:{examId}를 전송해야 한다.
     *
     * @param problemId 시험에 포함된 문제 ID
     * @param revisionId 저장한 고정 회차 ID
     * @param examId 권한 확인을 마친 시험 문맥
     * @return 지정 회차의 공개 본문과 예제
     */
    Content getContent(String problemId, String revisionId, String examId);

    /**
     * 시험의 회차 고정에 필요한 문제 요약이다.
     *
     * @param problemId 문제 ID
     * @param problemRevisionId 현재 공개 회차 ID
     * @param revisionNumber 회차 번호
     * @param title 제목
     * @param scope GENERAL·EXAM_ONLY
     * @param visibility PUBLIC·PRIVATE·ARCHIVED
     * @param isAvailable EXAM 목적에서 가용한지 여부
     */
    record Summary(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String title,
        String scope,
        String visibility,
        boolean isAvailable
    ) {
    }

    /**
     * 선택된 고정 회차의 공개 본문이다. 소비하지 않는 외부 필드는 DTO에 추가하지 않는다.
     * 이미지 식별자는 본문의 image 참조에서 추출하며 외부 응답에 없는 imageIds를 요구하지 않는다.
     *
     * @param problemId 문제 ID
     * @param problemRevisionId 고정 회차 ID
     * @param revisionNumber 회차 번호
     * @param title 제목
     * @param description 문제 설명 Markdown
     * @param constraints 제한 사항 Markdown
     * @param examples 입출력 예시 Markdown
     * @param functionSpec 함수 명세
     * @param executionLimits 고정 회차 실행 제한
     * @param samples 공개 예제 목록
     */
    record Content(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String title,
        String description,
        String constraints,
        String examples,
        FunctionSpec functionSpec,
        ExecutionLimits executionLimits,
        List<SampleTest> samples
    ) {
    }

    /**
     * 함수 명세의 공통 구조다. TypeSpec 별도 문서를 임의 재정의하지 않고 타입 객체를 보존한다.
     *
     * @param name 함수 이름
     * @param parameters 순서 있는 매개변수
     * @param returnType 문제 도메인이 정의한 반환 TypeSpec 객체
     */
    record FunctionSpec(String name, List<Parameter> parameters, Map<String, Object> returnType) {
    }

    /**
     * 매개변수의 이름과 원본 TypeSpec 객체를 유지한다.
     *
     * @param name 매개변수 이름
     * @param type 문제 도메인의 TypeSpec 객체
     */
    record Parameter(String name, Map<String, Object> type) {
    }

    /**
     * 회차에 저장한 실행 제한으로 현재 공통 설정을 덮어쓰지 않는다.
     *
     * @param timeMs CPU 제한 ms
     * @param memoryKb 메모리 제한 KB
     * @param wallTimeMs 실제 경과 제한 ms
     * @param outputLimitKb 출력 제한 KB, 외부 값이 없으면 null
     */
    record ExecutionLimits(int timeMs, int memoryKb, int wallTimeMs, Integer outputLimitKb) {
    }

    /**
     * 공개 실행 예제다. JSON 텍스트를 숫자로 변환하지 않아 큰 정수의 정밀도를 보존한다.
     *
     * @param testAssetId 공개 테스트 ID
     * @param argumentTexts 매개변수별 JSON 텍스트
     * @param expectedReturnText 기대 반환값 JSON 텍스트
     */
    record SampleTest(String testAssetId, Map<String, String> argumentTexts, String expectedReturnText) {
    }
}
