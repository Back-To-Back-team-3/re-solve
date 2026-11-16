package com.backtoback.judge.run.dto;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 시험·진단 내부 계약에 맞춘 실행 결과다(API 명세서 §3.3.8). */
public record ExamRunResponse(List<Result> results, Instant completedAt) {
    /**
     * 공개 예제와 직접 입력의 결과를 내부 Run 응답으로 변환한다.
     *
     * @param response 공통 실행 결과
     * @param completedAt 주입된 시계로 확인한 완료 시각
     * @return 입력별 순번·반환값·디버그 출력·측정값을 보존한 응답
     */
    public static ExamRunResponse from(RunResponse response, Instant completedAt) {
        var items = new ArrayList<Result>();
        int sampleIndex = 0;
        for (var item : response.results()) {
            int index = "SAMPLE".equals(item.source()) ? sampleIndex++ : item.index();
            int timeMs = item.timeMs() == null ? 0 : item.timeMs();
            int memoryKb
                = item.memoryKb() == null ? 0 : item.memoryKb().setScale(0, RoundingMode.CEILING).intValueExact();
            items
                .add(
                    new Result(
                        item.source(),
                        index,
                        item.actualReturnText(),
                        error(item, response.compileMessage()),
                        timeMs,
                        memoryKb,
                        item.stdout() == null ? "" : item.stdout()
                    )
                );
        }
        return new ExamRunResponse(List.copyOf(items), completedAt);
    }

    /**
     * 실행 실패만 오류로 변환하고 기대값 불일치는 반환값 확인을 허용한다.
     *
     * @param item 테스트별 실행 결과
     * @param compileMessage 컴파일 오류 메시지
     * @return 실행 오류 또는 정상 실행인 경우 null
     */
    private static String error(RunResponse.Result item, String compileMessage) {
        if (item.verdict() == null) {
            return null;
        }
        return switch (item.verdict()) {
            case AC, WA -> null;
            case CE -> compileMessage == null ? item.verdict().name() : compileMessage;
            default -> item.verdict().name();
        };
    }

    /** 예제와 직접 입력 목록의 순번, 반환값, 오류 및 정수 측정값이다. */
    public record Result(
        String type,
        int index,
        @JsonInclude(JsonInclude.Include.ALWAYS) String output,
        @JsonInclude(JsonInclude.Include.ALWAYS) String error,
        int timeMs,
        int memoryKb,
        String stdout
    ) {
    }
}
