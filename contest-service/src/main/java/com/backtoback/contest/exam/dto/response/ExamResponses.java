package com.backtoback.contest.exam.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.contest.exam.client.ProblemClient;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 시험 생성·조회·참가 API의 데이터 DTO를 묶는다. Entity와 DB의 Long·BigDecimal을 직접 노출하지 않는다.
 * ID·버전·점수는 문자열이며 서버 시각은 +09:00 및 마이크로초 6자리로 표현한다.
 * 상세 DTO에는 문제 식별자·본문을 두지 않아 시작 전 조회로 문제가 노출되는 것을 방지한다.
 * <p>기준 문서: API 명세서 v1.1 / §0.2 식별자(ID) 규칙, §4.1.1~§4.1.3, §4.2.1~§4.2.3: 응답 계약.
 * 서버 시각은 §4.0 개요와 JSON 예시의 serverNow를 사용하며 표의 serverTime 정정은 요청서로 구분한다.
 */
public final class ExamResponses {
    /**
     * DTO 이름 공간이며 인스턴스를 만들지 않는다.
     */
    private ExamResponses() {}

    /**
     * 시험 생성 결과와 고정한 문제 회차를 반환한다.
     *
     * @param examId 시험 ID
     * @param studyId 소속 스터디 ID
     * @param status 신규 시험 상태
     * @param examRevision 시험 설정 순번
     * @param version 낙관적 락 버전
     * @param resultRevision 결과 순번
     * @param startsAt 시작 시각
     * @param endsAt 전체 종료 시각
     * @param totalScore 소수점 둘째 자리 총점
     * @param problems 고정한 문제·회차·배점
     */
    public record Created(
        String examId,
        String studyId,
        ExamStatus status,
        String examRevision,
        String version,
        String resultRevision,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime startsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime endsAt,
        String totalScore,
        List<FixedProblem> problems
    ) {
    }

    /**
     * 새 시험에서 고정한 문제 회차와 표시 설정이다.
     *
     * @param problemId 문제 ID
     * @param problemRevisionId 고정 회차 ID
     * @param revisionNumber 표시용 회차 번호
     * @param score 소수점 둘째 자리 배점
     * @param displayOrder 1부터의 연속 표시 순서
     */
    public record FixedProblem(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String score,
        int displayOrder
    ) {
    }

    /**
     * 문제 식별자·본문이 없는 시험 목록 항목이다.
     *
     * @param examId 시험 ID
     * @param title 제목
     * @param status 시험 상태
     * @param startsAt 시작 시각
     * @param endsAt 종료 시각
     * @param problemCount 편입된 문제 수
     * @param myParticipantStatus 본인 참가 상태, 미등록은 null
     */
    public record Summary(
        String examId,
        String title,
        ExamStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime startsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime endsAt,
        int problemCount,
        ExamParticipantStatus myParticipantStatus
    ) {
    }

    /**
     * 공통 목록 메타데이터를 포함한다. HTTP 입력은 page 0부터, size 최대 100이다.
     *
     * @param content 현재 페이지 항목
     * @param page 요청 페이지
     * @param size 페이지 크기
     * @param totalElements 전체 항목 수
     * @param totalPages 전체 페이지 수
     * @param hasNext 다음 페이지 존재 여부
     */
    public record Page(List<Summary> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {
    }

    /**
     * 조회만으로 입장하지 않는 시험 상세다. 최초 입장 시각은 참가 정보에서 구분한다.
     *
     * @param examId 시험 ID
     * @param studyId 소속 스터디 ID
     * @param title 제목
     * @param mode 시험 방식
     * @param status 시험 상태
     * @param startsAt 시작 시각
     * @param endsAt 종료 시각
     * @param serverNow C 서버 시각
     * @param totalScore 배점 합계
     * @param problemCount 편입된 문제 수
     * @param examRevision 시험 설정 순번
     * @param version 낙관적 락 버전
     * @param resultRevision 결과 순번
     * @param myParticipation 활성 본인 참가 정보, 미등록은 null
     */
    public record Detail(
        String examId,
        String studyId,
        String title,
        ExamMode mode,
        ExamStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime startsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime endsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime serverNow,
        String totalScore,
        int problemCount,
        String examRevision,
        String version,
        String resultRevision,
        MyParticipation myParticipation
    ) {
    }

    /**
     * 상세 화면의 본인 활성 참가 정보다.
     *
     * @param participantId 참가 ID
     * @param status 참가 상태
     * @param enteredAt 최초 실제 입장 시각, 미입장은 null
     * @param personalEndsAt 개인 제출 마감
     */
    public record MyParticipation(
        String participantId,
        ExamParticipantStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime enteredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime personalEndsAt
    ) {
    }

    /**
     * 신규 또는 기존 참가 등록의 공통 응답 데이터다. 새 등록 여부는 HTTP 상태로 구분한다.
     *
     * @param participantId 참가 ID
     * @param examId 시험 ID
     * @param status 참가 상태
     * @param enteredAt 최초 입장 시각, 등록만 한 경우 null
     * @param personalEndsAt 개인 마감
     * @param serverNow C 서버 시각
     * @param participantRevision 참가 상태 순번
     */
    public record Registered(
        String participantId,
        String examId,
        ExamParticipantStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime enteredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime personalEndsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime serverNow,
        String participantRevision
    ) {
    }

    /**
     * 입장·재입장의 결과다. 후속 구현은 재입장에서도 최초 enteredAt을 유지해야 한다.
     *
     * @param participantId 참가 ID
     * @param status 입장 후 상태
     * @param enteredAt 최초 실제 입장 시각
     * @param personalEndsAt 개인 마감
     * @param serverNow C 서버 시각
     * @param participantRevision 참가 상태 순번
     */
    public record Started(
        String participantId,
        ExamParticipantStatus status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime enteredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime personalEndsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime serverNow,
        String participantRevision
    ) {
    }

    /**
     * 본인이 입장한 시험의 고정 회차 본문을 반환한다.
     *
     * @param examId 시험 ID
     * @param personalEndsAt 개인 마감
     * @param serverNow C 서버 시각
     * @param problems 숨김 테스트·정답 코드가 없는 공개 본문 목록
     */
    public record Problems(
        String examId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime personalEndsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX") OffsetDateTime serverNow,
        List<Problem> problems
    ) {
    }

    /**
     * 고정 회차 본문을 화면용으로 구성한다. 외부 서비스의 저장 파일·숨김 테스트 구조는 노출하지 않는다.
     *
     * @param problemId 문제 ID
     * @param problemRevisionId 고정 회차 ID
     * @param revisionNumber 표시 회차 번호
     * @param title 제목
     * @param score 배점, 계약상 null 허용
     * @param displayOrder 표시 순서
     * @param body 설명·제한·예시를 조합한 Markdown
     * @param functionSpec 함수 명세, TypeSpec의 하위 구조는 문제 도메인 값을 유지
     * @param executionLimits 고정 회차 실행 제한
     * @param sampleTests 공개 실행 예제만 포함
     * @param imageIds 본문에서 사용하는 이미지 ID
     */
    public record Problem(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String title,
        String score,
        int displayOrder,
        String body,
        ProblemClient.FunctionSpec functionSpec,
        ProblemClient.ExecutionLimits executionLimits,
        List<ProblemClient.SampleTest> sampleTests,
        List<String> imageIds
    ) {
    }
}
