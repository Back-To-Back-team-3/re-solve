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
  markAuthReady,
  memberApi,
  setAccessTokenSetter,
  setMemberGetter,
  setTokenGetter,
} from "../services/api";
import type { RunTestCase } from "@/services/run-inputs";
import type { JudgeJobFilter, JudgeResult, Language, Member, SubmissionCreateRequest } from "@/services/types";
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

/** 응답 유실 시 재전송할 원본과 최초 요청 시각을 보관한다. (API 명세서 §3.1.2) */
export interface PracticeSubmissionRequest {
  idempotencyKey: string;
  body: SubmissionCreateRequest;
  createdAt: number;
}

/** 접수된 제출의 조회 정보를 미접수 요청과 분리해 보존한다. */
export interface PracticeSubmissionAttempt {
  contextKey: string;
  idempotencyKey: string;
  createdAt: number;
  problemId: string;
  submissionId: string;
  result: JudgeResult | null;
}

interface Workspace {
  judgeJobFilter: JudgeJobFilter;
  setJudgeJobFilter: (filter: JudgeJobFilter) => void;
  judgeRecoveryReasons: Record<string, string | undefined>;
  setJudgeRecoveryReason: (key: string, reason: string) => void;
  viewPages: Record<string, number | undefined>;
  setViewPage: (key: string, page: number) => void;
  practiceRunInputs: Record<string, { useSamples: boolean; customInputs: RunTestCase[] } | undefined>;
  setPracticeRunInputs: (key: string, value: Partial<{ useSamples: boolean; customInputs: RunTestCase[] }>) => void;
  submissionAttempts: Record<string, number | undefined>;
  setSubmissionAttempt: (key: string, value: number) => void;
  member: Member | null;
  accessToken: string | null;
  authReady: boolean;
  drafts: Record<string, Draft>;
  practiceSubmissions: Record<string, PracticeSubmissionAttempt | undefined>;
  practiceRequests: Record<string, PracticeSubmissionRequest | undefined>;
  activePracticeSubmissions: Record<string, string | undefined>;
  setPracticeRequest: (key: string, request: PracticeSubmissionRequest) => void;
  acceptPracticeSubmission: (key: string, request: PracticeSubmissionRequest, submissionId: string) => boolean;
  selectPracticeSubmission: (key: string, submissionId: string) => void;
  setPracticeResult: (submissionId: string, result: JudgeResult) => void;
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
    judgeJobFilter: { sort: "updatedAt,desc" },
    setJudgeJobFilter: (judgeJobFilter) => set({ judgeJobFilter }),
    judgeRecoveryReasons: {},
    setJudgeRecoveryReason: (key, reason) => set((state) => ({ judgeRecoveryReasons: { ...state.judgeRecoveryReasons, [key]: reason } })),
    viewPages: {},
    setViewPage: (key, page) => set((state) => ({ viewPages: { ...state.viewPages, [key]: page } })),
    practiceRunInputs: {},
    setPracticeRunInputs: (key, value) => set((state) => ({ practiceRunInputs: { ...state.practiceRunInputs,
      [key]: { useSamples: true, customInputs: [], ...state.practiceRunInputs[key], ...value } } })),
    submissionAttempts: {},
    setSubmissionAttempt: (key, value) => set((state) => ({ submissionAttempts: { ...state.submissionAttempts, [key]: value } })),
    practiceSubmissions: {},
    practiceRequests: {},
    activePracticeSubmissions: {},
    setPracticeRequest: (key, request) => set((state) => ({
      practiceRequests: { ...state.practiceRequests, [key]: request },
    })),
    acceptPracticeSubmission: (key, request, submissionId) => {
      let accepted = false;
      set((state) => {
        // 새 요청을 시작한 뒤 도착한 이전 응답은 현재 접수로 반영하지 않는다.
        if (state.practiceRequests[key]?.idempotencyKey !== request.idempotencyKey) return state;
        accepted = true;
        return {
          practiceRequests: { ...state.practiceRequests, [key]: undefined },
          activePracticeSubmissions: { ...state.activePracticeSubmissions, [key]: submissionId },
          practiceSubmissions: { ...state.practiceSubmissions, [submissionId]: {
            contextKey: key, idempotencyKey: request.idempotencyKey, createdAt: request.createdAt,
            problemId: request.body.problemId, submissionId, result: state.practiceSubmissions[submissionId]?.result ?? null,
          } },
        };
      });
      return accepted;
    },
    selectPracticeSubmission: (key, submissionId) => set((state) =>
      state.practiceSubmissions[submissionId]?.contextKey === key
        ? { activePracticeSubmissions: { ...state.activePracticeSubmissions, [key]: submissionId } } : state),
    setPracticeResult: (submissionId, result) => set((state) => {
      const accepted = state.practiceSubmissions[submissionId];
      return accepted ? { practiceSubmissions: { ...state.practiceSubmissions, [submissionId]: { ...accepted, result } } } : state;
    }),
    language: "python3",
    fontSize: 14,
    activeProblems: {},
    setMember: (member) => set({ member }),
    clearMember: () => set({ judgeJobFilter: { sort: "updatedAt,desc" }, judgeRecoveryReasons: {}, viewPages: {}, practiceRunInputs: {}, submissionAttempts: {}, member: null, accessToken: null, practiceSubmissions: {}, practiceRequests: {}, activePracticeSubmissions: {} }),
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
    const { setAccessToken, setMember, clearMember } = store.getState();
    const setAuthReady = (ready: boolean) => {
      store.getState().setAuthReady(ready);
      if (ready) markAuthReady();
    };

    setTokenGetter(() => store.getState().accessToken);
    setMemberGetter(() => store.getState().member);
    setAccessTokenSetter(setAccessToken);

    if (process.env.NEXT_PUBLIC_DEV_BYPASS_USER_ID) {
      memberApi
        .getMe()
        .then((member) => setMember(member))
        .catch(() => clearMember())
        .finally(() => setAuthReady(true));
    } else {
      authApi
        .refresh()
        .then(({ accessToken }) => {
          setAccessToken(accessToken);
          return memberApi.getMe();
        })
        .then((member) => setMember(member))
        .catch(() => clearMember())
        .finally(() => setAuthReady(true));
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 비인증 가드 — authReady 이후 비로그인 상태에서 보호 경로에 접근하면 로그인 페이지로 이동한다.
  useEffect(() => {
    const { authReady, member } = store.getState();
    if (!authReady || member !== null || isPublicPath) return;
    const returnTo = window.location.pathname + window.location.search;
    router.push(`/login?returnTo=${encodeURIComponent(returnTo)}`);
  });

  const authReady = useStore(store, (s) => s.authReady);
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
