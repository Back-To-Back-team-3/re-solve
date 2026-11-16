"use client";
import { createContext, useContext, useState, type ReactNode } from "react";
import { createStore, useStore } from "zustand";
import { compareDiagnosisSeq, diagnosisDraftRequest, restoreDiagnosisBuffer, type DiagnosisBuffer } from "@/services/diagnosis";
import type { DiagnosisDetail, DiagnosisDraftRequest, Language } from "@/services/types";

interface DiagnosisState {
  detail: DiagnosisDetail;
  activeProblem: string;
  buffers: Record<string, DiagnosisBuffer>;
  select: (id: string) => void;
  restore: (id: string, raw: string | null) => void;
  ensure: (id: string, language: Language, sourceCode: string) => void;
  edit: (id: string, language: Language, sourceCode: string) => void;
  acknowledge: (id: string, seq: string) => void;
  setPending: (id: string, body: DiagnosisDraftRequest | null) => void;
  merge: (detail: DiagnosisDetail) => void;
  close: () => void;
}

/** 문항 순번과 미확인 제출 원본을 유지하고 폴링 응답이 편집 중인 코드를 덮어쓰지 않게 한다. */
export const createDiagnosisStore = (detail: DiagnosisDetail) => createStore<DiagnosisState>((set) => ({
  detail, activeProblem: detail.problems[0]?.problemId ?? "",
  buffers: Object.fromEntries(detail.problems.map((problem) => [problem.problemId, restoreDiagnosisBuffer(problem, null)])),
  select: (activeProblem) => set({ activeProblem }),
  restore: (id, raw) => set((state) => {
    const problem = state.detail.problems.find((item) => item.problemId === id);
    return problem ? { buffers: { ...state.buffers, [id]: restoreDiagnosisBuffer(problem, raw) } } : state;
  }),
  ensure: (id, language, sourceCode) => set((state) => {
    const buffer = state.buffers[id];
    if (!buffer || buffer.draft) return state;
    const submittedSeq = state.detail.problems.find((problem) => problem.problemId === id)?.lastSubmission?.draftSeq ?? "0";
    const seq = String(BigInt(submittedSeq) + 1n);
    return { buffers: { ...state.buffers, [id]: { ...buffer, draft: { language, sourceCode, seq }, codes: { ...buffer.codes, [language]: sourceCode } } } };
  }),
  edit: (id, language, sourceCode) => set((state) => {
    const buffer = state.buffers[id];
    if (!buffer || state.detail.status !== "IN_PROGRESS"
      || (buffer.draft?.language === language && buffer.draft.sourceCode === sourceCode)) return state;
    const maximum = BigInt(buffer.draft?.seq ?? "0") > BigInt(buffer.savedSeq) ? buffer.draft!.seq : buffer.savedSeq;
    const seq = String(BigInt(maximum) + 1n);
    return { buffers: { ...state.buffers, [id]: { ...buffer, draft: { language, sourceCode, seq }, codes: { ...buffer.codes, [language]: sourceCode } } } };
  }),
  acknowledge: (id, seq) => set((state) => {
    const buffer = state.buffers[id];
    if (!buffer || compareDiagnosisSeq(seq, buffer.savedSeq) < 0) return state;
    return { buffers: { ...state.buffers, [id]: { ...buffer, savedSeq: seq } } };
  }),
  setPending: (id, pending) => set((state) => ({ buffers: {
    ...state.buffers, [id]: { ...state.buffers[id], pending },
  } })),
  merge: (next) => set((state) => {
    const rank = { IN_PROGRESS: 0, CLOSED: 1, SCORED: 2 };
    if (rank[next.status] < rank[state.detail.status] || Date.parse(next.serverNow) < Date.parse(state.detail.serverNow)) return state;
    const buffers = { ...state.buffers };
    for (const problem of next.problems) {
      const buffer = buffers[problem.problemId];
      if (!buffer || !problem.draft) continue;
      const draft = !buffer.draft || compareDiagnosisSeq(problem.draft.seq, buffer.draft.seq) >= 0
        ? diagnosisDraftRequest(problem.draft) : buffer.draft;
      buffers[problem.problemId] = { ...buffer,
        codes: { ...buffer.codes, [draft.language]: draft.sourceCode },
        savedSeq: compareDiagnosisSeq(problem.draft.seq, buffer.savedSeq) > 0 ? problem.draft.seq : buffer.savedSeq,
        draft,
      };
    }
    return { detail: next, buffers };
  }),
  close: () => set((state) => ({ detail: { ...state.detail, status: "CLOSED", result: null } })),
}));
const Context = createContext<ReturnType<typeof createDiagnosisStore> | null>(null);
export function DiagnosisProvider({ detail, children }: { detail: DiagnosisDetail; children: ReactNode }) {
  const [store] = useState(() => createDiagnosisStore(detail));
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useDiagnosisApi() {
  const store = useContext(Context);
  if (!store) throw new Error("진단 Provider가 필요합니다.");
  return store;
}
export function useDiagnosis() {
  return useStore(useDiagnosisApi());
}
