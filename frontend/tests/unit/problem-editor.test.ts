import { describe, expect, it } from "vitest";
import { availableProblemLanguages, formatProblemType, toEditorProblem } from "../../src/services/problem-editor";
import type { ProblemDetail } from "../../src/services/types";

const detail: ProblemDetail = {
  problemId: "1", problemRevisionId: "2", revisionNumber: 1,
  title: "배열 합", difficulty: 1, tags: [], publishedAt: "2026-11-13T00:00:00.000000+09:00",
  solvedStatus: "UNSOLVED", description: "설명", constraints: "제한", examples: "예시",
  functionSpec: {
    name: "solution",
    parameters: [{ name: "a", type: { kind: "INT32" } }, { name: "b", type: { kind: "INT32" } }],
    returnType: { kind: "INT32" },
  },
  executionLimits: { timeMs: 1000, memoryKb: 262144, wallTimeMs: 3000, outputLimitKb: 64 },
  supportedLanguages: ["java", "python3"],
  starterCodes: [{ language: "java", sourceCode: "java-code" }, { language: "python3", sourceCode: "python-code" }],
  samples: [{ testAssetId: "3", argumentTexts: { b: "2", a: "1" }, expectedReturnText: "3" }],
};

describe("문제 에디터 표시 계약", () => {
  it("배열·객체·위치별·반복 타입의 중첩 구조를 유지한다", () => {
    expect(formatProblemType({ kind: "ARRAY", elementType: {
      kind: "OBJECT", properties: { items: { kind: "ARRAY", positionTypes: [{ kind: "INT32" }, { kind: "STRING" }] } },
    } })).toBe("ARRAY<{ items: [INT32, STRING] }>");
    expect(formatProblemType({ kind: "ARRAY", repeatTypes: [{ kind: "BOOLEAN" }, { kind: "ARRAY", elementType: { kind: "FLOAT64" } }] }))
      .toBe("ARRAY<repeat(BOOLEAN, ARRAY<FLOAT64>)>");
    expect(formatProblemType({ kind: "ARRAY", positionTypes: [] })).toBe("[]");
  });

  it("기본 코드가 있어도 미통과 언어·비활성 실행 환경은 선택하지 않는다", () => {
    expect(availableProblemLanguages(detail, [
      { language: "java", displayName: "Java 17", runtimeVersion: "17", enabled: false },
      { language: "python3", displayName: "Python 3", runtimeVersion: "3", enabled: true },
      { language: "cpp", displayName: "C++17", runtimeVersion: "17", enabled: true },
    ])).toEqual(["python3"]);
    expect(availableProblemLanguages({ ...detail, starterCodes: [] }, [
      { language: "python3", displayName: "Python 3", runtimeVersion: "3", enabled: true },
    ])).toEqual([]);
    expect(availableProblemLanguages(detail, [])).toEqual([]);
  });

  it("회차·기본 코드를 보존하고 예제 인수를 함수 매개변수 순서로 표시한다", () => {
    expect(toEditorProblem(detail)).toMatchObject({
      problemRevisionId: "2", starterCodes: { java: "java-code", python3: "python-code" },
      samples: [{ input: "1, 2", expected: "3" }],
    });
  });
});
