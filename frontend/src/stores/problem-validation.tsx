"use client";
import { createContext, useContext, useState, type ReactNode } from "react";
import { createStore, useStore } from "zustand";
import type { AdminProblemDetail, Language, ProblemValidationRequest } from "@/services/types";

type Pending = { json: string; key: string; body: ProblemValidationRequest };
interface ValidationState {
  language: Language;
  drafts: Partial<Record<Language, string>>;
  pending: Partial<Record<Language, Pending>>;
  validationId: string;
  selectLanguage: (language: Language) => void;
  setCode: (sourceCode: string) => void;
  setPending: (request: Pending) => void;
  setValidationId: (validationId: string) => void;
}
const createValidation = (detail: AdminProblemDetail) => createStore<ValidationState>((set) => ({
  language: "java", drafts: Object.fromEntries(detail.referenceSolutions.map((code) => [code.language, code.sourceCode])),
  pending: {}, validationId: detail.languageResults[0]?.validationId ?? "",
  selectLanguage: (language) => set({ language }),
  setCode: (sourceCode) => set((state) => ({ drafts: { ...state.drafts, [state.language]: sourceCode } })),
  setPending: (request) => set((state) => ({ pending: { ...state.pending, [request.body.language]: request } })),
  setValidationId: (validationId) => set({ validationId }),
}));
const Context = createContext<ReturnType<typeof createValidation> | null>(null);
export function ProblemValidationProvider({ detail, children }: { detail: AdminProblemDetail; children: ReactNode }) {
  const [store] = useState(() => createValidation(detail));
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useProblemValidation() {
  const store = useContext(Context);
  if (!store) throw new Error("문제 검증 Provider가 필요합니다.");
  return useStore(store);
}
