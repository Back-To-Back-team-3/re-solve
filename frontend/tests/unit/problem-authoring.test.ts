import { expect, it } from "vitest";
import { parseProblemContent } from "../../src/services/problem-authoring";

const fields = {
  title: "문제", description: "설명", constraints: "제한", examples: "예시", difficulty: 1, tagIds: "1,2",
  functionSpec: JSON.stringify({ name: "solution", parameters: [{ name: "a", type: { kind: "ARRAY", elementType: { kind: "INT32" } } }], returnType: { kind: "INT32" } }),
  testCases: JSON.stringify([
    { testAssetId: "ignored", isSample: true, argumentTexts: { a: "[1]" }, expectedReturnText: "1" },
    { isSample: false, argumentTexts: { a: "[2]" }, expectedReturnText: "2" },
  ]), executionLimits: "null",
};
it("출제 JSON을 검증하고 조회 전용 파일 ID를 전송하지 않는다", () => {
  const result = parseProblemContent(fields);
  expect(result.tagIds).toEqual(["1", "2"]);
  expect(result.executionLimits).toBeNull();
  expect(result.testCases[0]).not.toHaveProperty("testAssetId");
});
it("잘못된 함수·공개 및 숨김 누락·메모리와 반환값 상한 초과를 거절한다", () => {
  expect(() => parseProblemContent({ ...fields, functionSpec: "{}" })).toThrow("함수 명세");
  expect(() => parseProblemContent({ ...fields, testCases: "[]" })).toThrow("테스트");
  for (const limits of [{ memoryKb: 512001 }, { outputLimitKb: 257 }]) {
    expect(() => parseProblemContent({ ...fields, executionLimits: JSON.stringify(limits) })).toThrow("범위");
  }
  expect(parseProblemContent({ ...fields, executionLimits: '{"memoryKb":512000,"outputLimitKb":256}' }).executionLimits)
    .toMatchObject({ memoryKb: 512000, outputLimitKb: 256 });
});
