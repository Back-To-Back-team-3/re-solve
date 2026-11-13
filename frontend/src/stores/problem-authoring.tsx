"use client";
import { createContext, useContext, useState, type ReactNode } from "react";
import { createStore, useStore } from "zustand";
import type { AdminProblemDetail, ProblemWriteContent } from "@/services/types";
import { readProblemTests, type ProblemTestInputs } from "@/services/problem-test-inputs";

type Fields = {
  title: string; description: string; constraints: string; examples: string;
  difficulty: number; tagIds: string; executionLimits: string;
};
interface AuthoringState {
  conflict: boolean;
  latest: AdminProblemDetail | null;
  setConflict: (conflict: boolean) => void;
  setLatest: (latest: AdminProblemDetail | null) => void;
  scope: "GENERAL" | "EXAM_ONLY";
  functionSpec: ProblemWriteContent["functionSpec"];
  fields: Fields;
  tests: ProblemTestInputs;
  rowVersion?: number;
  setScope: (scope: "GENERAL" | "EXAM_ONLY") => void;
  setFunctionSpec: (value: ProblemWriteContent["functionSpec"]) => void;
  setFields: (value: Fields | ((fields: Fields) => Fields)) => void;
  setTests: (tests: ProblemTestInputs) => void;
  setRowVersion: (rowVersion: number) => void;
}
const createAuthoring = (initial: AdminProblemDetail | null) => {
  const functionSpec = initial?.functionSpec
    ?? { name: "solution" as const, parameters: [{ name: "a", type: { kind: "INT32" } }], returnType: { kind: "INT32" } };
  return createStore<AuthoringState>((set) => ({
    conflict: false, latest: null,
    setConflict: (conflict) => set({ conflict }), setLatest: (latest) => set({ latest }),
    scope: initial?.scope ?? "GENERAL", functionSpec, rowVersion: initial?.rowVersion,
    fields: { title: initial?.title ?? "", description: initial?.description ?? "", constraints: initial?.constraints ?? "",
      examples: initial?.examples ?? "", difficulty: initial?.difficulty ?? 1, tagIds: initial?.tagIds.join(",") ?? "",
      executionLimits: JSON.stringify(initial?.executionLimits ?? null, null, 2) },
    tests: readProblemTests(functionSpec, initial?.testCases
      ?? [{ isSample: true, argumentTexts: { a: "1" }, expectedReturnText: "1" },
        { isSample: false, argumentTexts: { a: "2" }, expectedReturnText: "2" }]),
    setScope: (scope) => set({ scope }), setFunctionSpec: (functionSpec) => set({ functionSpec }),
    setFields: (value) => set((state) => ({ fields: typeof value === "function" ? value(state.fields) : value })),
    setTests: (tests) => set({ tests }), setRowVersion: (rowVersion) => set({ rowVersion }),
  }));
};
const Context = createContext<ReturnType<typeof createAuthoring> | null>(null);
export function ProblemAuthoringProvider({ initial, children }: { initial: AdminProblemDetail | null; children: ReactNode }) {
  const [store] = useState(() => createAuthoring(initial));
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useProblemAuthoring() {
  const store = useContext(Context);
  if (!store) throw new Error("문제 작성 Provider가 필요합니다.");
  return useStore(store);
}
