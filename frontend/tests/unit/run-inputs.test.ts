import { expect, it } from "vitest";
import { runInputs } from "@/services/run-inputs";

it("직접 입력의 JSON 원문과 함수 인수 이름을 보존한다", () => {
  expect(runInputs(["a"], [{ a: " [1, 2] " }], false))
    .toEqual([{ argumentTexts: { a: " [1, 2] " } }]);
  expect(runInputs([], [{}, {}, {}, {}, {}], false)).toHaveLength(5);
});
it("실행 대상 누락·6개 입력·잘못된 JSON과 누락 인수를 거절한다", () => {
  expect(() => runInputs([], [], false)).toThrow("공개 예제");
  expect(() => runInputs([], [{}, {}, {}, {}, {}, {}], true)).toThrow("최대 5개");
  expect(() => runInputs(["a"], [{ a: "bad" }], false)).toThrow("직접 입력 1의 a");
  expect(() => runInputs(["a"], [{}], false)).toThrow("직접 입력 1의 a");
});
