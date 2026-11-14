package com.backtoback.problem.problem.service;

import java.util.List;
import java.util.Map;

import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

/** 최초 등록 테스트에서 공유하는 최소 유효 입력. */
public final class ProblemCreateFixtures {
    private ProblemCreateFixtures() {}

    /**
     * 공개·숨김 각 한 건의 정수 함수를 구성한다.
     *
     * @return 기본 제한을 사용하는 등록 입력
     */
    public static ProblemCreateRequest request() {
        var type = new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null);
        return new ProblemCreateRequest(
            ProblemScope.GENERAL,
            "두 배",
            "설명",
            "제약",
            "예시",
            1,
            List.of(),
            new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("value", type)), type),
            null,
            List
                .of(
                    new TestCaseRequest(true, Map.of("value", "1"), "2"),
                    new TestCaseRequest(false, Map.of("value", "2"), "4")
                )
        );
    }
}
