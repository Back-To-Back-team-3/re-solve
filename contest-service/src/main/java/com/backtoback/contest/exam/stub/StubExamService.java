package com.backtoback.contest.exam.stub;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.backtoback.contest.exam.client.ProblemClient;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.request.CreateExamRequest;
import com.backtoback.contest.exam.dto.response.ExamResponses;
import com.backtoback.contest.exam.service.ExamService;
import com.backtoback.contest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 여섯 API의 성공·오류 데이터 형태를 확인하는 무상태 개발 대역이다.
 * 생성·등록·입장 요청은 DB나 메모리에 상태를 저장하지 않고 매 요청에 예시를 만든다.
 * 실제 멱등·권한·시간 판정·이벤트·최초 입장 유지가 구현된 것은 아니다.
 * <p>기준 문서: API 명세서 v1.1 / §4.1.1~§4.1.3, §4.2.1~§4.2.3: 예시 응답과 HTTP 계약.
 */
@Service
@Profile("dev & exam-stub & !prod & !production")
@RequiredArgsConstructor
public class StubExamService implements ExamService {
    // DB 식별자가 아닌 개발 응답 예시의 시험·참가 식별자.
    private static final String EXAM_ID = "55";
    private static final String PARTICIPANT_ID = "411";
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("image:([1-9][0-9]*)");

    private final ProblemClient problemClient;
    private final ExamStubScenario scenario;
    private final Clock clock;

    /**
     * 입력한 문제별 공개 회차 요약을 고정 응답으로 변환한다. DB 저장은 하지 않는다.
     *
     * @param studyId 응답할 스터디 ID
     * @param memberId 요청자, 실제 권한 판정에는 사용하지 않음
     * @param request 검증한 생성 입력
     * @return 요청과 배점·고정 회차가 연결된 생성 예시
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public ExamResponses.Created create(String studyId, String memberId, CreateExamRequest request) {
        scenario.raiseIfRequested();
        Map<String, ProblemClient.Summary> summaries
            = problemClient
                .getExamSummaries(request.problems().stream().map(CreateExamRequest.Problem::problemId).toList())
                .stream()
                .collect(Collectors.toMap(ProblemClient.Summary::problemId, Function.identity()));
        List<ExamResponses.FixedProblem> problems
            = request
                .problems()
                .stream()
                .sorted(Comparator.comparing(CreateExamRequest.Problem::displayOrder))
                .map(problem -> fixedProblem(problem, summaries.get(problem.problemId())))
                .toList();
        BigDecimal total
            = request
                .problems()
                .stream()
                .map(problem -> new BigDecimal(problem.score()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExamResponses.Created(
            EXAM_ID,
            studyId,
            ExamStatus.SCHEDULED,
            "1",
            "0",
            "0",
            request.startsAt().atZoneSameInstant(clock.getZone()).toOffsetDateTime(),
            request.endsAt().atZoneSameInstant(clock.getZone()).toOffsetDateTime(),
            total.toPlainString(),
            problems
        );
    }

    /**
     * 한 건의 예시 목록에 상태 필터·페이지를 적용한다. 실제 DB 조회·사용자 열람 판정은 하지 않는다.
     *
     * @param studyId 요청 스터디
     * @param memberId 요청자
     * @param status 선택 상태
     * @param page 요청 페이지
     * @param size 요청 크기
     * @param sort 단일 예시이므로 정렬 결과에 영향 없음
     * @return 목록 필터와 페이지 계약 예시
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public ExamResponses.Page list(
        String studyId,
        String memberId,
        ExamStatus status,
        int page,
        int size,
        String sort
    ) {
        scenario.raiseIfRequested();
        OffsetDateTime now = now();
        boolean matches = status == null || status == ExamStatus.SCHEDULED;
        List<ExamResponses.Summary> content
            = matches && page == 0 ? List
                .of(
                    new ExamResponses.Summary(
                        EXAM_ID,
                        "모의 코테 예시",
                        ExamStatus.SCHEDULED,
                        now.plusDays(1),
                        now.plusDays(1).plusMinutes(90),
                        3,
                        null
                    )
                ) : List.of();
        return new ExamResponses.Page(content, page, size, matches ? 1 : 0, matches ? 1 : 0, false);
    }

    /**
     * 문제 정보 없는 상세를 만든다. 조회로 입장을 기록하지 않는다.
     *
     * @param examId 응답에 사용할 시험 ID
     * @param memberId 요청자
     * @return 미등록 상태와 서버 시각을 포함한 상세 예시
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public ExamResponses.Detail getDetail(String examId, String memberId) {
        scenario.raiseIfRequested();
        OffsetDateTime now = now();
        return new ExamResponses.Detail(
            examId,
            "12",
            "모의 코테 예시",
            ExamMode.FIXED,
            ExamStatus.SCHEDULED,
            now.plusDays(1),
            now.plusDays(1).plusMinutes(90),
            now,
            "100.00",
            3,
            "1",
            "0",
            "0",
            null
        );
    }

    /**
     * 헤더 시나리오로 새 참가·기존 참가 상태 코드를 확인할 데이터를 만든다.
     *
     * @param examId 시험 ID
     * @param memberId 요청자
     * @return 신규 여부와 등록 응답 예시
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public Registration register(String examId, String memberId) {
        scenario.raiseIfRequested();
        OffsetDateTime now = now();
        ExamResponses.Registered data
            = new ExamResponses.Registered(
                PARTICIPANT_ID,
                examId,
                ExamParticipantStatus.REGISTERED,
                null,
                now.plusDays(1).plusMinutes(90),
                now,
                "1"
            );
        return new Registration(!scenario.isExistingParticipant(), data);
    }

    /**
     * 입장 성공의 상태·시각 형식을 만든다. 실제 이전 입장 상태는 보관하지 않는다.
     *
     * @param examId 시험 ID
     * @param memberId 요청자
     * @return STARTED 상태와 개인 마감 예시
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public ExamResponses.Started start(String examId, String memberId) {
        scenario.raiseIfRequested();
        OffsetDateTime now = now();
        return new ExamResponses.Started(
            PARTICIPANT_ID,
            ExamParticipantStatus.STARTED,
            now,
            now.plusMinutes(90),
            now,
            "2"
        );
    }

    /**
     * 고정한 예시 회차에서 화면 본문·공개 실행 예제를 구성한다.
     *
     * @param examId 호출 문맥
     * @param memberId 요청자, 실제 참가·입장 판정은 후속 구현
     * @return 공개 문제 세 건과 서버 시각
     * @throws BusinessException 선택한 오류 시나리오
     */
    @Override
    public ExamResponses.Problems getProblems(String examId, String memberId) {
        scenario.raiseIfRequested();
        List<ProblemClient.Summary> summaries = problemClient.getExamSummaries(List.of("101", "102", "103"));
        List<ExamResponses.Problem> problems
            = java.util.stream.IntStream
                .range(0, summaries.size())
                .mapToObj(
                    index -> problemResponse(examId, summaries.get(index), index + 1, index == 2 ? "40.00" : "30.00")
                )
                .toList();
        OffsetDateTime now = now();
        return new ExamResponses.Problems(examId, now.plusMinutes(90), now, problems);
    }

    /**
     * HTTP 입력의 배점·순서와 외부 요약의 회차를 연결한다.
     *
     * @param problem 요청 문제 설정
     * @param summary 개발 대역이 반환한 공개 회차
     * @return 생성 응답의 고정 문제
     */
    private ExamResponses.FixedProblem fixedProblem(CreateExamRequest.Problem problem, ProblemClient.Summary summary) {
        return new ExamResponses.FixedProblem(
            problem.problemId(),
            summary.problemRevisionId(),
            summary.revisionNumber(),
            problem.score(),
            problem.displayOrder()
        );
    }

    /**
     * 지정 회차의 설명·제한·예시를 조합하고 공개 예제와 이미지 참조만 전달한다.
     *
     * @param examId 외부 호출 시험 문맥
     * @param summary 고정한 회차 요약
     * @param order 표시 순서
     * @param score 문자열 배점
     * @return 시험 화면 문제
     */
    private ExamResponses.Problem problemResponse(
        String examId,
        ProblemClient.Summary summary,
        int order,
        String score
    ) {
        ProblemClient.Content content
            = problemClient.getContent(summary.problemId(), summary.problemRevisionId(), examId);
        String body = String.join("\n\n", content.description(), content.constraints(), content.examples());
        List<String> imageIds
            = IMAGE_REFERENCE.matcher(body).results().map(match -> match.group(1)).distinct().toList();
        return new ExamResponses.Problem(
            content.problemId(),
            content.problemRevisionId(),
            content.revisionNumber(),
            content.title(),
            score,
            order,
            body,
            content.functionSpec(),
            content.executionLimits(),
            content.samples(),
            imageIds
        );
    }

    /**
     * 요청마다 서버 시각을 마이크로초 정밀도로 얻는다.
     *
     * @return 설정한 서울 시간 Clock의 현재 순간
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
