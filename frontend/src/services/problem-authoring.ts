import type { ProblemFunctionParamType, ProblemWriteContent } from "@/services/types";

export function objectValue(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function typeSpec(value: unknown): value is ProblemFunctionParamType {
  if (!objectValue(value)) return false;
  if (value.kind === "ARRAY") {
    const forms = [value.elementType, value.positionTypes, value.repeatTypes].filter((item) => item !== undefined);
    if (forms.length !== 1) return false;
    if (value.elementType !== undefined) return typeSpec(value.elementType);
    const rows = value.positionTypes ?? value.repeatTypes;
    return Array.isArray(rows) && (value.repeatTypes === undefined || rows.length > 0) && rows.every(typeSpec);
  }
  if (value.kind === "OBJECT") return objectValue(value.properties) && Object.values(value.properties).every(typeSpec);
  return typeof value.kind === "string" && ["INT32", "INT64", "FLOAT32", "FLOAT64", "STRING", "BOOLEAN"].includes(value.kind);
}

/** JSON 입력을 검증하고 전송 계약의 필드만 구성한다. */
export function parseProblemContent(fields: {
  title: string; description: string; constraints: string; examples: string; difficulty: number;
  tagIds: string; functionSpec: string; testCases: string; executionLimits: string;
}): ProblemWriteContent {
  const fn: unknown = JSON.parse(fields.functionSpec);
  if (!objectValue(fn) || fn.name !== "solution" || !Array.isArray(fn.parameters) || !typeSpec(fn.returnType)) {
    throw new Error("함수 명세는 solution 이름·매개변수·반환 TypeSpec이 필요합니다.");
  }
  const parameters = fn.parameters.map((param: unknown) => {
    if (!objectValue(param) || typeof param.name !== "string" || !param.name.trim() || !typeSpec(param.type)) {
      throw new Error("매개변수 이름과 TypeSpec을 확인해 주세요.");
    }
    return { name: param.name, type: param.type };
  });
  if (new Set(parameters.map((param) => param.name)).size !== parameters.length) throw new Error("매개변수 이름이 중복됩니다.");
  const tests: unknown = JSON.parse(fields.testCases);
  if (!Array.isArray(tests) || !tests.length || tests.length > 50) throw new Error("테스트는 1~50개가 필요합니다.");
  const testCases = tests.map((test: unknown) => {
    if (!objectValue(test) || typeof test.isSample !== "boolean" || !objectValue(test.argumentTexts)
      || typeof test.expectedReturnText !== "string") throw new Error("테스트의 공개 여부·입력·기대값을 확인해 주세요.");
    const args = Object.fromEntries(Object.entries(test.argumentTexts).map(([key, value]) => {
      if (typeof value !== "string") throw new Error("테스트 입력은 JSON 원문 문자열이어야 합니다.");
      JSON.parse(value); return [key, value];
    }));
    if (Object.keys(args).length !== parameters.length || parameters.some((param) => !(param.name in args))) {
      throw new Error("테스트 입력 이름은 함수 매개변수와 같아야 합니다.");
    }
    JSON.parse(test.expectedReturnText);
    return { isSample: test.isSample, argumentTexts: args, expectedReturnText: test.expectedReturnText };
  });
  if (!testCases.some((test) => test.isSample) || !testCases.some((test) => !test.isSample)) {
    throw new Error("공개·숨김 테스트가 각각 한 개 이상 필요합니다.");
  }
  const limits: unknown = JSON.parse(fields.executionLimits);
  let executionLimits: ProblemWriteContent["executionLimits"] = null;
  if (limits !== null) {
    if (!objectValue(limits)) throw new Error("실행 제한은 객체 또는 null이어야 합니다.");
    const number = (name: string, fallback: number, min: number, max = Number.MAX_SAFE_INTEGER) => {
      const value = limits[name] ?? fallback;
      if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) throw new Error(`${name} 범위를 확인해 주세요.`);
      return value;
    };
    const timeMs = number("timeMs", 2000, 1000);
    executionLimits = { timeMs, memoryKb: number("memoryKb", 262144, 1, 512000),
      wallTimeMs: number("wallTimeMs", timeMs + 5000, 1), outputLimitKb: number("outputLimitKb", 64, 1, 256) };
  }
  const content = { title: fields.title, description: fields.description, constraints: fields.constraints,
    examples: fields.examples, difficulty: fields.difficulty,
    tagIds: fields.tagIds.split(",").map((value) => value.trim()).filter(Boolean),
    functionSpec: { name: "solution" as const, parameters, returnType: fn.returnType }, executionLimits, testCases };
  const bytes = new TextEncoder();
  const sizes = testCases.map((test) => bytes.encode(Object.values(test.argumentTexts).join("") + test.expectedReturnText).length);
  if (sizes.some((size) => size > 256 * 1024) || sizes.reduce((sum, size) => sum + size, 0) > 4 * 1024 * 1024) {
    throw new Error("테스트 입력·기대값은 건당 256KiB·전체 4MiB 이하여야 합니다.");
  }
  return content;
}

/** 서버 내부 메시지를 추측하지 않고 공개 오류 메시지만 표시한다. */
export function actionMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message
    : objectValue(failure) && typeof failure.message === "string" ? failure.message : "요청을 처리하지 못했습니다.";
}
