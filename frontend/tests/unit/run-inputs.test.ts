import { expect, it } from "vitest";
import { createRunInput, runInputs, runExpectedOutputs, type RunInputValue } from "@/services/run-inputs";

const scalar = (text: string): RunInputValue => ({ kind: "SCALAR", text });
const array = (...items: RunInputValue[]): RunInputValue => ({ kind: "ARRAY", items });

it("문자열·참거짓·큰 정수·소수의 사용자 값을 정밀도 손실 없이 직렬화한다", () => {
  const parameters = [
    { name: "text", inputType: { kind: "STRING" } },
    { name: "flag", inputType: { kind: "BOOLEAN" } },
    { name: "number", inputType: { kind: "INT64" } },
    { name: "decimal", inputType: { kind: "FLOAT64" } },
  ];
  expect(runInputs(parameters, [{
    text: scalar('  "hello"\n세계  '), flag: scalar("true"),
    number: scalar("9007199254740993"), decimal: scalar("1.0000000000000001"),
  }], false)).toEqual([{ argumentTexts: {
    text: JSON.stringify('  "hello"\n세계  '), flag: "true",
    number: "9007199254740993", decimal: "1.0000000000000001",
  } }]);
  expect(runInputs([parameters[0]], [{ text: scalar("") }], false)[0].argumentTexts.text).toBe('""');
});

it("배열·객체·위치별 값·반복 패턴을 중첩 구조 그대로 변환한다", () => {
  const integer = { kind: "INT32" };
  const text = { kind: "STRING" };
  const type = { kind: "OBJECT", properties: {
    matrix: { kind: "ARRAY", elementType: { kind: "ARRAY", elementType: integer } },
    tuple: { kind: "ARRAY", positionTypes: [integer, text] },
    repeated: { kind: "ARRAY", repeatTypes: [integer, text] },
  } };
  expect(createRunInput(type)).toEqual({ kind: "OBJECT", fields: {
    matrix: array(), tuple: array(scalar(""), scalar("")), repeated: array(),
  } });
  const value: RunInputValue = { kind: "OBJECT", fields: {
    matrix: array(array(scalar("01"), scalar("-2")), array()),
    tuple: array(scalar("3"), scalar("a,b")),
    repeated: array(scalar("4"), scalar("x"), scalar("5"), scalar("y")),
  } };
  expect(runInputs([{ name: "data", inputType: type }], [{ data: value }], false))
    .toEqual([{ argumentTexts: { data: '{"matrix":[[1,-2],[]],"tuple":[3,"a,b"],"repeated":[4,"x",5,"y"]}' } }]);
});

it("실행 대상 누락·6개 입력·잘못된 숫자·범위 초과와 누락 인수를 거절한다", () => {
  const p = [{ name: "a", inputType: { kind: "INT32" } }];
  expect(runInputs([], [{}, {}, {}, {}, {}], false)).toHaveLength(5);
  expect(() => runInputs([], [], false)).toThrow("공개 예제");
  expect(() => runInputs([], [{}, {}, {}, {}, {}, {}], true)).toThrow("최대 5개");
  for (const value of ["", "bad", "1.2", "2147483648"]) {
    expect(() => runInputs(p, [{ a: scalar(value) }], false)).toThrow("직접 입력 1의 a");
  }
  expect(() => runInputs(p, [{}], false)).toThrow("직접 입력 1의 a");
  expect(() => runInputs([{ name: "a" }], [{}], false)).toThrow("입력 정보");
  expect(() => runInputs([{ name: "a", inputType: { kind: "INT64" } }],
    [{ a: scalar("9223372036854775808") }], false)).toThrow("허용 범위");
});

it("선택 기대값은 미입력과 빈 문자열을 구분하고 실행 시점의 문자열로 고정한다", () => {
  const rows = [{ arguments: {}, expectedOutput: scalar("9007199254740993") }, { arguments: {} }];
  const snapshot = runExpectedOutputs({ kind: "INT64" }, rows);
  expect(snapshot).toEqual(["9007199254740993", null]);
  rows[0].expectedOutput = scalar("10");
  expect(snapshot[0]).toBe("9007199254740993");
  expect(runExpectedOutputs({ kind: "STRING" }, [{ arguments: {}, expectedOutput: scalar("") }])).toEqual(['""']);
  expect(runExpectedOutputs({ kind: "ARRAY", elementType: { kind: "INT32" } },
    [{ arguments: {}, expectedOutput: array() }])).toEqual(["[]"]);
  expect(runExpectedOutputs(undefined, [{ arguments: {} }])).toEqual([null]);
});


it.each([
  ["FLOAT32", "1e100"], ["FLOAT32", "1e-100"], ["FLOAT64", "1e-999"],
])("%s의 범위 초과와 0으로 소실되는 %s를 거절한다", (kind, text) => {
  expect(() => runInputs([{ name: "a", inputType: { kind } }], [{ a: scalar(text) }], false)).toThrow("표현 범위");
});
it.each([["FLOAT32", "1e-45"], ["FLOAT64", "5e-324"], ["FLOAT32", "-0.0"], ["FLOAT64", "0e-999"]])(
  "%s의 유한한 비정규화 수·0 원문 %s를 보존한다", (kind, text) => {
    expect(runInputs([{ name: "a", inputType: { kind } }], [{ a: scalar(text) }], false)[0].argumentTexts.a).toBe(text);
  },
);
