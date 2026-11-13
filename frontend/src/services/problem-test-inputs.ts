import { readRunInput, runInputText } from "@/services/run-inputs";
import type { ProblemFunctionParamType, ProblemWriteContent } from "@/services/types";

type FunctionSpec = ProblemWriteContent["functionSpec"];
export type ProblemTestInput = { isSample: boolean; argumentsText: string; expectedText: string };
export type ProblemTestInputs = { definition: string; rows: ProblemTestInput[] };

/** 입력 중인 미완성 JSON은 저장 전까지 그대로 유지한다. */
export function newProblemTest(isSample = false): ProblemTestInput {
  return { isSample, argumentsText: "", expectedText: "" };
}

/** 기존 JSON 원문을 연결해 큰 정수를 Number로 변환하지 않고 편집한다. */
export function readProblemTests(fn: FunctionSpec, tests: ProblemWriteContent["testCases"]): ProblemTestInputs {
  return { definition: JSON.stringify(fn), rows: tests.map((test) => ({
    isSample: test.isSample,
    argumentsText: `{${fn.parameters.map((param) => `${JSON.stringify(param.name)}:${test.argumentTexts[param.name]}`).join(",")}}`,
    expectedText: test.expectedReturnText,
  })) };
}

/** JSON.parse가 덮어쓰는 중복 키도 중첩 객체별로 검출한다. 문법은 먼저 검증한다. */
function validateJson(text: string) {
  try { JSON.parse(text); } catch { throw new Error("유효한 JSON을 입력해 주세요."); }
  const tokens = text.match(/"(?:\\.|[^"\\])*"|[{}\[\]:,]/g) ?? [];
  const stack: (Set<string> | null)[] = [];
  tokens.forEach((token, index) => {
    if (token === "{") stack.push(new Set());
    else if (token === "[") stack.push(null);
    else if (token === "}" || token === "]") stack.pop();
    else if (token.startsWith('"') && tokens[index + 1] === ":") {
      const key: string = JSON.parse(token);
      const keys = stack.at(-1);
      if (keys?.has(key)) throw new Error(`객체 필드 ${key}가 중복됩니다.`);
      keys?.add(key);
    }
  });
}

function readValue(type: ProblemFunctionParamType, text: string, name: string) {
  try { validateJson(text); return readRunInput(type, text); }
  catch (failure) { throw new Error(`${name}: ${failure instanceof Error ? failure.message : "값을 확인해 주세요."}`); }
}

/** JSON 입력을 함수 명세로 검증하고 API의 인수별 JSON 텍스트로 분리한다. */
export function writeProblemTests(fn: FunctionSpec, tests: ProblemTestInputs): ProblemWriteContent["testCases"] {
  if (tests.definition !== JSON.stringify(fn)) throw new Error("함수 명세가 변경되었습니다. 테스트 입력을 확인하고 초기화해 주세요.");
  const argumentsType: ProblemFunctionParamType = { kind: "OBJECT",
    properties: Object.fromEntries(fn.parameters.map((param) => [param.name, param.type])) };
  return tests.rows.map((test, index) => {
    const argumentsValue = readValue(argumentsType, test.argumentsText, `테스트 ${index + 1} 입력값`);
    if (argumentsValue.kind !== "OBJECT") throw new Error("입력값은 매개변수 이름을 키로 하는 객체여야 합니다.");
    return {
      isSample: test.isSample,
      argumentTexts: Object.fromEntries(fn.parameters.map((param) =>
        [param.name, runInputText(param.type, argumentsValue.fields[param.name], `테스트 ${index + 1} ${param.name}`)])),
      expectedReturnText: runInputText(fn.returnType,
        readValue(fn.returnType, test.expectedText, `테스트 ${index + 1} 기대 출력`), `테스트 ${index + 1} 기대 출력`),
    };
  });
}
