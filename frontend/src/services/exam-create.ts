import type { CreateExamProblem } from "@/services/types";

/** 배점을 소수점 두 자리 문자열로 정규화하고 서버 범위를 검증한다(§4.1.1). */
export function normalizeExamScore(value: string): string {
  const match = /^(\d{1,4})(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) throw new Error("배점은 0.01~9999.99 사이의 소수점 둘째 자리까지 입력해 주세요.");
  const integer = Number(match[1]);
  const fraction = (match[2] ?? "").padEnd(2, "0");
  if (integer * 100 + Number(fraction) < 1) throw new Error("배점은 0.01 이상이어야 합니다.");
  return `${integer}.${fraction}`;
}

/** 선택 순서와 배점 문자열을 검증하여 시험 생성 요청으로 변환한다. */
export function examCreateProblems(selected: { problemId: string; score: string }[]): CreateExamProblem[] {
  if (selected.length < 1 || selected.length > 20) throw new Error("문제는 1~20개 선택해 주세요.");
  if (new Set(selected.map((item) => item.problemId)).size !== selected.length) throw new Error("같은 문제는 한 번만 선택할 수 있습니다.");
  const problems = selected.map((item, index) => ({ problemId: item.problemId,
    score: normalizeExamScore(item.score), displayOrder: index + 1 }));
  const total = problems.reduce((sum, item) => sum + Number(item.score.replace(".", "")), 0);
  if (total > 999999) throw new Error("배점 합계는 9999.99 이하여야 합니다.");
  return problems;
}
