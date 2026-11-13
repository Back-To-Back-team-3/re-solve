import type { JudgeLanguage, Language, Problem, ProblemDetail, ProblemFunctionParamType } from "@/services/types";

/** TypeSpec의 중첩 구조를 보존해 함수 명세에 표시한다. */
export function formatProblemType(type: ProblemFunctionParamType): string {
  if (type.kind === "ARRAY") {
    if (type.elementType) return `ARRAY<${formatProblemType(type.elementType)}>`;
    if (type.positionTypes) return `[${type.positionTypes.map(formatProblemType).join(", ")}]`;
    if (type.repeatTypes) return `ARRAY<repeat(${type.repeatTypes.map(formatProblemType).join(", ")})>`;
  }
  if (type.kind === "OBJECT" && type.properties) {
    return `{ ${Object.entries(type.properties).map(([name, value]) => `${name}: ${formatProblemType(value)}`).join(", ")} }`;
  }
  return type.kind;
}

/** 통과 언어·기본 코드·현재 실행 환경이 모두 준비된 언어만 선택한다. */
export function availableProblemLanguages(problem: ProblemDetail, runtimes: JudgeLanguage[]): Language[] {
  return problem.supportedLanguages.filter((language) =>
    problem.starterCodes.some((code) => code.language === language && code.sourceCode.trim().length > 0)
    && runtimes.some((runtime) => runtime.language === language && runtime.enabled),
  );
}

/** 고정 회차와 기본 코드를 유지하면서 기존 에디터의 표시 모델로 변환한다. */
export function toEditorProblem(detail: ProblemDetail): Problem {
  return {
    id: detail.problemId,
    problemRevisionId: detail.problemRevisionId,
    title: detail.title,
    difficulty: detail.difficulty,
    tags: detail.tags,
    description: detail.description,
    constraints: detail.constraints,
    examples: detail.examples,
    functionSpec: {
      name: "solution",
      parameters: detail.functionSpec.parameters.map((param) => ({ name: param.name, type: formatProblemType(param.type), inputType: param.type })),
      returnType: formatProblemType(detail.functionSpec.returnType),
      returnInputType: detail.functionSpec.returnType,
    },
    starterCodes: {
      java: detail.starterCodes.find((code) => code.language === "java")?.sourceCode ?? "",
      python3: detail.starterCodes.find((code) => code.language === "python3")?.sourceCode ?? "",
      cpp: detail.starterCodes.find((code) => code.language === "cpp")?.sourceCode ?? "",
    },
    samples: detail.samples.map((sample) => ({
      input: detail.functionSpec.parameters.map((param) => sample.argumentTexts[param.name]).join(", "),
      expected: sample.expectedReturnText,
      argumentTexts: sample.argumentTexts,
    })),
  };
}
