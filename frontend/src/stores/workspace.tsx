"use client";
import { createContext, useContext, useState, type ReactNode } from "react";
import { createStore, useStore } from "zustand";
import type { Language } from "../services/types";
export interface Draft {
  code: string;
  revisionId: string;
  savedAt: number;
}
export function draftKey(
  user: string,
  problem: string,
  language: Language,
  exam?: string,
) {
  return JSON.stringify([user, exam || "practice", problem, language]);
}
export function readDraft(key: string): Draft | null {
  const raw = localStorage.getItem("resolve:draft:" + key);
  if (!raw) return null;
  const d = JSON.parse(raw);
  return typeof d.code === "string" && typeof d.revisionId === "string"
    ? d
    : null;
}
export function saveDraft(key: string, draft: Draft) {
  localStorage.setItem("resolve:draft:" + key, JSON.stringify(draft));
}
interface Workspace {
  drafts: Record<string, Draft>;
  language: Language;
  fontSize: number;
  activeProblems: Record<string, string>;
  setDraft: (key: string, d: Draft) => void;
  setLanguage: (l: Language) => void;
  setFontSize: (n: number) => void;
  setActive: (exam: string, id: string) => void;
}
const createWorkspace = () =>
  createStore<Workspace>((set) => ({
    drafts: {},
    language: "python3",
    fontSize: 14,
    activeProblems: {},
    setDraft: (key, d) => set((s) => ({ drafts: { ...s.drafts, [key]: d } })),
    setLanguage: (language) => {
      set({ language });
    },
    setFontSize: (fontSize) => set({ fontSize }),
    setActive: (exam, id) =>
      set((s) => ({ activeProblems: { ...s.activeProblems, [exam]: id } })),
  }));
const Context = createContext<ReturnType<typeof createWorkspace> | null>(null);
export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createWorkspace);
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useWorkspace<T>(selector: (s: Workspace) => T) {
  const store = useContext(Context);
  if (!store) throw new Error("WorkspaceProvider missing");
  return useStore(store, selector);
}
export function useWorkspaceApi() {
  const store = useContext(Context);
  if (!store) throw new Error("WorkspaceProvider missing");
  return store;
}
export function examDrafts(
  drafts: Record<string, Draft>,
  userId: string,
  examId: string,
) {
  const selected: Record<
    string,
    {
      problemId: string;
      language: Language;
      sourceCode: string;
      savedAt: number;
    }
  > = {};
  for (const [key, draft] of Object.entries(drafts)) {
    try {
      const [user, exam, problemId, language] = JSON.parse(key);
      if (user !== userId || exam !== examId) continue;
      if (!selected[problemId] || selected[problemId].savedAt < draft.savedAt)
        selected[problemId] = {
          problemId,
          language,
          sourceCode: draft.code,
          savedAt: draft.savedAt,
        };
    } catch {}
  }
  return Object.values(selected);
}
