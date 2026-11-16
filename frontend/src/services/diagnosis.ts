import type { DiagnosisDraftRequest, DiagnosisProblem, Language } from "@/services/types";

export function diagnosisDraftKey(userId: string, diagnosisId: string, problemId: string) {
  return `re-solve:diagnosis:${userId}:${diagnosisId}:${problemId}`;
}

export function isDiagnosisLanguage(value: unknown): value is Language {
  return value === "java" || value === "python3" || value === "cpp";
}

export function isDiagnosisDraft(value: unknown): value is DiagnosisDraftRequest {
  if (typeof value !== "object" || value === null) return false;
  return "language" in value && isDiagnosisLanguage(value.language)
    && "sourceCode" in value && typeof value.sourceCode === "string"
    && "seq" in value && typeof value.seq === "string" && /^[0-9]{1,18}$/.test(value.seq);
}

/** 복구 응답의 savedAt 같은 조회 전용 필드를 쓰기 요청에 포함하지 않는다. */
export function diagnosisDraftRequest(draft: DiagnosisDraftRequest): DiagnosisDraftRequest {
  return { language: draft.language, sourceCode: draft.sourceCode, seq: draft.seq };
}

/** 순번은 문자열로 유지해 JavaScript 정수 정밀도로 이전 Draft를 선택하지 않도록 한다. */
export function compareDiagnosisSeq(a: string, b: string): number {
  return BigInt(a) > BigInt(b) ? 1 : BigInt(a) < BigInt(b) ? -1 : 0;
}

export type DiagnosisBuffer = {
  revisionId: string;
  codes: Partial<Record<Language, string>>;
  draft: DiagnosisDraftRequest | null;
  savedSeq: string;
  pending: DiagnosisDraftRequest | null;
};

/** 동일 순번에서는 서버를 정본으로 삼고 더 큰 로컬 Draft만 복구한다. (API 명세서 §1.5.2) */
export function restoreDiagnosisBuffer(problem: DiagnosisProblem, raw: string | null): DiagnosisBuffer {
  let draft: DiagnosisDraftRequest | null = problem.draft ? diagnosisDraftRequest(problem.draft) : null;
  let pending: DiagnosisDraftRequest | null = null;
  const codes: Partial<Record<Language, string>> = {};
  try {
    const local: unknown = raw ? JSON.parse(raw) : null;
    if (local && typeof local === "object" && "revisionId" in local && local.revisionId === problem.problemRevisionId) {
      if ("codes" in local && typeof local.codes === "object" && local.codes !== null) {
        for (const [language, code] of Object.entries(local.codes)) {
          if (isDiagnosisLanguage(language) && typeof code === "string") codes[language] = code;
        }
      }
      if ("draft" in local && isDiagnosisDraft(local.draft)
        && (!draft || compareDiagnosisSeq(local.draft.seq, draft.seq) > 0)) draft = diagnosisDraftRequest(local.draft);
      if ("pending" in local && isDiagnosisDraft(local.pending)) pending = diagnosisDraftRequest(local.pending);
    }
  } catch { /* 손상된 로컬 기록은 서버 Draft로 복구한다. */ }
  if (draft) codes[draft.language] = draft.sourceCode;
  return { revisionId: problem.problemRevisionId, codes, draft, pending, savedSeq: problem.draft?.seq ?? "0" };
}

export function diagnosisCodeError(code: string, submission = false): string {
  if (submission && !code.trim()) return "제출할 코드를 입력해 주세요.";
  return new TextEncoder().encode(code).length > 64 * 1024 ? "코드는 64KiB 이하로 입력해 주세요." : "";
}

export function diagnosisStatusLabel(status: string): string {
  return ({ IN_PROGRESS: "진행 중", CLOSED: "채점 대기", SCORED: "결과 확정",
    ACCEPTED: "접수 완료", REQUESTED: "채점 요청됨", JUDGED: "채점 완료", FAILED: "채점 실패" })[status] ?? status;
}
