import type { ExamProblem, Problem } from "@/services/types";
import { formatProblemType } from "@/services/problem-editor";

/** 시험이 고정한 회차와 함수 명세를 그대로 풀이 화면에 전달한다. */
export function toEditorExamProblem(ep: ExamProblem): Problem {
  return {
    id: ep.problemId,
    problemRevisionId: ep.problemRevisionId,
    title: ep.title,
    difficulty: 0,
    tags: [],
    description: ep.body,
    constraints: "",
    examples: "",
    functionSpec: {
      name: ep.functionSpec.name,
      parameters: ep.functionSpec.parameters.map(({ name, type }) => ({ name, type: formatProblemType(type), inputType: type })),
      returnType: formatProblemType(ep.functionSpec.returnType), returnInputType: ep.functionSpec.returnType,
    },
    starterCodes: { java: "", python3: "", cpp: "" },
    samples: ep.sampleTests.map((sample) => ({
      input: ep.functionSpec.parameters.map(({ name }) => sample.argumentTexts[name]).join(", "),
      expected: sample.expectedReturnText, argumentTexts: sample.argumentTexts,
    })),
  };
}
