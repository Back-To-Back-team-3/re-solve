package com.backtoback.contest.exam.stub;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.backtoback.contest.exam.client.MemberClient;
import com.backtoback.contest.exam.client.ProblemClient;
import com.backtoback.contest.exam.client.StudyClient;

/**
 * DB·외부 HTTP 없이 계약을 확인할 개발 전용 의존성 예시를 구성한다.
 * 회원·스터디 예시는 권한 판정에 사용하지 않으며 문제 예시는 회차 고정·본문 DTO 연결 확인에만 사용한다.
 * <p>기준 문서: API 명세서 v1.1 / §1.7.1, §5.7.1, §2.6.3, §2.6.5: 외부 의존 데이터 형식.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & exam-stub & !prod & !production")
public class ExamStubConfiguration {
    /**
     * stub 시각을 서울 시간으로 제공한다. 테스트에서는 고정 Clock을 사용해 시각을 결정할 수 있다.
     *
     * @return 서울 시간 Clock
     */
    @Bean
    public Clock examStubClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    /**
     * 회원 상태 호출 경계를 외부 서비스 없이 확인한다.
     *
     * @return 요청 ID를 포함한 ACTIVE·USER 회원 예시
     */
    @Bean
    public MemberClient stubMemberClient() {
        return memberId -> new MemberClient.MemberStatus(memberId, "ACTIVE", "USER");
    }

    /**
     * 스터디 역할 조회의 데이터 예시를 제공한다. 실제 생성 권한 검증을 수행하지 않는다.
     *
     * @return 회원 7이 LEADER인 스터디 예시
     */
    @Bean
    public StudyClient stubStudyClient() {
        return studyId -> new StudyClient.Members(
            studyId,
            "ACTIVE",
            List.of(new StudyClient.Member("7", "40", "LEADER"))
        );
    }

    /**
     * 문제 회차 요약과 공개 본문 호출 계약을 연결한다. ID·회차는 저장 결과가 아닌 예시다.
     *
     * @return HTTP 호출 없는 문제 서비스 대역
     */
    @Bean
    public ProblemClient stubProblemClient() {
        return new StubProblemClient();
    }

    /**
     * 선택 요청 순서에 따라 예시 회차를 부여하고 공개 본문만 제공한다.
     */
    private static class StubProblemClient implements ProblemClient {
        /**
         * 선택한 ID마다 다른 예시 회차를 만들어 고정 회차 응답 형식을 확인한다.
         *
         * @param problemIds 선택할 문제 ID
         * @return GENERAL·PUBLIC 가용 요약 목록
         */
        @Override
        public List<Summary> getExamSummaries(List<String> problemIds) {
            return IntStream
                .range(0, problemIds.size())
                .mapToObj(
                    index -> new Summary(
                        problemIds.get(index),
                        Integer.toString(1001 + index),
                        1,
                        "덧셈 예시 " + (index + 1),
                        "GENERAL",
                        "PUBLIC",
                        true
                    )
                )
                .toList();
        }

        /**
         * 지정한 회차 ID를 유지해 본문을 반환한다. 실제 원격 회차 존재·권한 검증은 하지 않는다.
         *
         * @param problemId 선택한 문제
         * @param revisionId 고정 회차
         * @param examId 호출 문맥
         * @return 공개 함수 명세·실행 예제·이미지 참조 본문
         */
        @Override
        public Content getContent(String problemId, String revisionId, String examId) {
            Map<String, Object> integerType = Map.of("kind", "INT32");
            FunctionSpec function
                = new FunctionSpec(
                    "solution",
                    List.of(new Parameter("a", integerType), new Parameter("b", integerType)),
                    integerType
                );
            SampleTest sample = new SampleTest("501", Map.of("a", "1", "b", "2"), "3");
            return new Content(
                problemId,
                revisionId,
                1,
                "두 수의 합",
                "## 문제\n두 정수 a와 b의 합을 반환하세요.\n\n![덧셈 예시](image:701)",
                "- 입력 범위: -1000 <= a, b <= 1000",
                "| a | b | 반환값 |\n|---|---|---|\n| 1 | 2 | 3 |",
                function,
                new ExecutionLimits(2000, 262144, 7000, 64),
                List.of(sample)
            );
        }
    }
}
