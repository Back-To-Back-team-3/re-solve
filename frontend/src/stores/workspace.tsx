"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { usePathname, useRouter } from "next/navigation";
import { createStore, useStore } from "zustand";
import {
  authApi,
  memberApi,
  setAccessTokenSetter,
  setTokenGetter,
} from "../services/api";
import type { Language, Member } from "../services/types";
import { Loading } from "../components/ui";
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
const PUBLIC_PATHS = ["/", "/login", "/auth/callback"];

interface Workspace {
  member: Member | null;
  accessToken: string | null;
  authReady: boolean;
  drafts: Record<string, Draft>;
  language: Language;
  fontSize: number;
  activeProblems: Record<string, string>;
  setMember: (member: Member) => void;
  clearMember: () => void;
  setAccessToken: (token: string) => void;
  setAuthReady: (ready: boolean) => void;
  setDraft: (key: string, d: Draft) => void;
  setLanguage: (l: Language) => void;
  setFontSize: (n: number) => void;
  setActive: (exam: string, id: string) => void;
}
const createWorkspace = () =>
  createStore<Workspace>((set) => ({
    member: null,
    accessToken: null,
    authReady: false,
    drafts: {},
    language: "python3",
    fontSize: 14,
    activeProblems: {},
    setMember: (member) => set({ member }),
    clearMember: () => set({ member: null, accessToken: null }),
    setAccessToken: (accessToken) => set({ accessToken }),
    setAuthReady: (authReady) => set({ authReady }),
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
  const router = useRouter();
  const pathname = usePathname();
  const isPublicPath = PUBLIC_PATHS.some((p) => pathname.startsWith(p));

  // 앱 시작 시 1회 — Refresh Token으로 인증 상태를 복원하고 회원 정보를 가져온다.
  useEffect(() => {
    const { setAccessToken, setMember, clearMember, setAuthReady } =
      store.getState();

    setTokenGetter(() => store.getState().accessToken);
    setAccessTokenSetter(setAccessToken);

    authApi
      .refresh()
      .then(({ accessToken }) => {
        setAccessToken(accessToken);
        return memberApi.getMe();
      })
      .then((member) => setMember(member))
      .catch(() => clearMember())
      .finally(() => setAuthReady(true));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 비인증 가드 — authReady 이후 비로그인 상태에서 보호 경로에 접근하면 로그인 페이지로 이동한다.
  useEffect(() => {
    const { authReady, member } = store.getState();
    if (!authReady || member !== null || isPublicPath) return;
    const returnTo = window.location.pathname + window.location.search;
    router.push(`/login?returnTo=${encodeURIComponent(returnTo)}`);
  });

  const { authReady } = store.getState();
  if (!authReady && !isPublicPath) {
    return <Loading />;
  }

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
