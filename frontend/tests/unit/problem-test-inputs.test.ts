import { expect, it } from "vitest";
import { newProblemTest, readProblemTests, writeProblemTests } from "@/services/problem-test-inputs";
import type { ProblemWriteContent } from "@/services/types";

const fn: ProblemWriteContent["functionSpec"] = {
  name: "solution", parameters: [{ name: "items", type: { kind: "ARRAY", elementType: { kind: "INT64" } } }],
  returnType: { kind: "OBJECT", properties: { text: { kind: "STRING" }, passed: { kind: "BOOLEAN" } } },
};
const cases: ProblemWriteContent["testCases"] = [{
  isSample: false, argumentTexts: { items: "[9223372036854775807,-9223372036854775808]" },
  expectedReturnText: '{"text":"123과 \\"456\\"","passed":true}',
}];

it("기존 중첩 테스트의 INT64 정밀도와 문자열을 유지하며 폼으로 왕복한다", () => {
  expect(writeProblemTests(fn, readProblemTests(fn, cases))).toEqual(cases);
});

it("미완성 JSON은 편집 상태에 유지하고 저장할 때 입력 오류로 안내한다", () => {
  const numberFn: ProblemWriteContent["functionSpec"] = {
    name: "solution", parameters: [{ name: "a", type: { kind: "INT32" } }], returnType: { kind: "INT32" },
  };
  const test = { ...newProblemTest(), argumentsText: '{"a":' };
  expect(() => writeProblemTests(numberFn, { definition: JSON.stringify(numberFn), rows: [test] })).toThrow("테스트 1 입력값");
  expect(test.argumentsText).toBe('{"a":');
});

it("다른 매개변수·중첩 타입 불일치·이스케이프한 중복 키를 거절한다", () => {
  const form = readProblemTests(fn, cases);
  for (const argumentsText of ['{"other":[]}', '{"items":["1"]}', '{"items":[],"\\u0069tems":[]}']) {
    expect(() => writeProblemTests(fn, { ...form, rows: [{ ...form.rows[0], argumentsText }] })).toThrow();
  }
  expect(() => writeProblemTests(fn, { ...form, rows: [{ ...form.rows[0],
    expectedText: '{"text":"a","passed":true,"passed":false}' }] })).toThrow("중복");
});

it("함수 명세가 바뀌면 기존 입력을 자동 변환하거나 유실하지 않고 저장을 막는다", () => {
  const tests = readProblemTests(fn, cases);
  expect(() => writeProblemTests({ ...fn, parameters: [] }, tests)).toThrow("함수 명세가 변경");
  expect(writeProblemTests(fn, tests)).toEqual(cases);
});
