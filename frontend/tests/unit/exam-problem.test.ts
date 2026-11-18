import { expect, it } from "vitest";
import { toEditorExamProblem } from "@/services/exam-problem";
import { problemDetail } from "../e2e/practice-fixture";

it("문제 ID와 다른 고정 회차·중첩 함수 타입·공개 예제를 보존한다", () => {
  const problem = toEditorExamProblem({ problemId: "1001", problemRevisionId: "9001", revisionNumber: 2,
    title: "시험", body: "고정 본문", score: "100.00", displayOrder: 1, functionSpec: problemDetail.functionSpec,
    executionLimits: problemDetail.executionLimits, sampleTests: problemDetail.samples, imageIds: [] });
  expect(problem.problemRevisionId).toBe("9001");
  expect(problem.functionSpec.parameters[0].inputType).toEqual({ kind: "ARRAY", elementType: { kind: "INT32" } });
  expect(problem.samples[0]).toMatchObject({ expected: "3", argumentTexts: { items: "[1,2]" } });
});
