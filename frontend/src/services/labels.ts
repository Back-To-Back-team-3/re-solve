import type { JudgeState, Language, SolvedStatus, Verdict } from "./types";

/** 테스트별 결과에는 코드 오답이 아닌 시스템 실패를 뜻하는 `SYSTEM_ERROR`가 추가된다 (API 명세서 2.3.11 data.tests[].verdict). */
export type TestVerdict = Verdict | "SYSTEM_ERROR";

/** 판정 표시 문구 (API 명세서 0.5 상태값 요약). */
export const verdictLabel: Record<TestVerdict, string> = {
  AC: "정답",
  WA: "오답",
  TLE: "시간 초과",
  MLE: "메모리 초과",
  RE: "런타임 오류",
  CE: "컴파일 오류",
  SYSTEM_ERROR: "시스템 오류",
};

/** 채점 작업 상태 표시 문구 (API 명세서 0.5 상태값 요약 > 제출 status). */
export const judgeStatusLabel: Record<JudgeState, string> = {
  QUEUED: "대기 중",
  JUDGING: "채점 중",
  RETRY_WAITING: "재시도 대기",
  COMPLETED: "채점 완료",
  FAILED: "시스템 오류",
};

/** `UNSOLVED`는 시도했지만 풀지 못한 경우도 포함하므로 "미도전"이 아니라 "미해결"로 표시한다. */
export const solvedStatusLabel: Record<SolvedStatus, string> = {
  SOLVED: "해결",
  UNSOLVED: "미해결",
};

export const languageLabel: Record<Language, string> = {
  java: "Java",
  python3: "Python",
  cpp: "C++",
};

/** 명세에 없는 값이 오면 빈칸 대신 원래 값을 보여 줘 결함이 화면에서 드러나게 한다. */
export function labelOf<K extends string>(
  map: Record<K, string>,
  value: string,
): string {
  return (map as Record<string, string>)[value] ?? value;
}
