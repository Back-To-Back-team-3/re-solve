import { createRunInput, readRunInput, runInputText, type RunInputRow, type RunInputValue } from "@/services/run-inputs";
import type { ProblemWriteContent } from "@/services/types";

type FunctionSpec = ProblemWriteContent["functionSpec"];
export type ProblemTestInput = { isSample: boolean; arguments: RunInputRow; expected: RunInputValue };
export type ProblemTestInputs = { definition: string; rows: ProblemTestInput[] };

/** 함수 명세에 맞는 빈 테스트를 생성한다. 입력 중인 빈 숫자도 그대로 유지한다. */
export function newProblemTest(fn: FunctionSpec, isSample = false): ProblemTestInput {
  return { isSample, arguments: Object.fromEntries(fn.parameters.map((param) => [param.name, createRunInput(param.type)])),
    expected: createRunInput(fn.returnType) };
}

/** 기존 회차의 테스트를 파일 ID 없이 타입별 편집 값으로 복원한다. */
export function readProblemTests(fn: FunctionSpec, tests: ProblemWriteContent["testCases"]): ProblemTestInputs {
  return { definition: JSON.stringify(fn), rows: tests.map((test) => ({
    isSample: test.isSample,
    arguments: Object.fromEntries(fn.parameters.map((param) => [param.name, readRunInput(param.type, test.argumentTexts[param.name])])),
    expected: readRunInput(fn.returnType, test.expectedReturnText),
  })) };
}

/** 저장 시점에만 입력을 검증·직렬화해 원래 관리자 API 계약으로 전송한다. */
export function writeProblemTests(fn: FunctionSpec, tests: ProblemTestInputs): ProblemWriteContent["testCases"] {
  if (tests.definition !== JSON.stringify(fn)) throw new Error("함수 명세가 변경되었습니다. 테스트 입력을 확인하고 초기화해 주세요.");
  return tests.rows.map((test, index) => ({
    isSample: test.isSample,
    argumentTexts: Object.fromEntries(fn.parameters.map((param) =>
      [param.name, runInputText(param.type, test.arguments[param.name], `테스트 ${index + 1} ${param.name}`)])),
    expectedReturnText: runInputText(fn.returnType, test.expected, `테스트 ${index + 1} 기대 출력`),
  }));
}
