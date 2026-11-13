import type { Language } from "@/services/types";

/** 기존 시험 Draft 키에서 코드와 서버 저장 순번을 복원한다. */
export function readExamDraft(examId: string, problemId: string): { code: string; seq: string } | null {
  try {
    const raw = localStorage.getItem(`re-solve:editor:draft:exam:${examId}:${problemId}`);
    if (!raw) return null;
    const draft: unknown = JSON.parse(raw);
    if (typeof draft !== "object" || draft === null || !("code" in draft) || typeof draft.code !== "string") return null;
    return { code: draft.code, seq: "seq" in draft && typeof draft.seq === "string" ? draft.seq : "1" };
  } catch { return null; }
}

/** 서버 저장 성공 후 기존 시험 Draft 키에 코드와 다음 순번을 보관한다. */
export function writeExamDraft(examId: string, problemId: string, language: Language, code: string, seq: string): void {
  try {
    localStorage.setItem(`re-solve:editor:draft:exam:${examId}:${problemId}`, JSON.stringify({ language, code, seq }));
  } catch {}
}
