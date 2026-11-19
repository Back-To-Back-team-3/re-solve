import { expect, it } from "vitest";
import { examCreateProblems, normalizeExamScore } from "@/services/exam-create";

it("기본·수정 배점을 소수점 두 자리 문자열로 정규화한다", () => {
  expect(normalizeExamScore("100")).toBe("100.00");
  expect(normalizeExamScore("32.5")).toBe("32.50");
  expect(normalizeExamScore("0.01")).toBe("0.01");
  expect(normalizeExamScore("9999.99")).toBe("9999.99");
  expect(examCreateProblems([{ problemId: "9", score: "100" }, { problemId: "3", score: "32.5" }])).toEqual([
    { problemId: "9", score: "100.00", displayOrder: 1 }, { problemId: "3", score: "32.50", displayOrder: 2 },
  ]);
});
it("범위 밖·잘못된 배점·중복 문제·합계 초과 요청을 막는다", () => {
  for (const score of ["", "0", "-1", "1e2", "1.001", "10000", "NaN"]) expect(() => normalizeExamScore(score)).toThrow();
  expect(() => examCreateProblems([])).toThrow();
  expect(() => examCreateProblems(Array.from({ length: 21 }, (_, index) => ({ problemId: String(index), score: "1" })))).toThrow();
  expect(() => examCreateProblems([{ problemId: "1", score: "1" }, { problemId: "1", score: "2" }])).toThrow();
  expect(() => examCreateProblems([{ problemId: "1", score: "9999.99" }, { problemId: "2", score: "0.01" }])).toThrow();
});
