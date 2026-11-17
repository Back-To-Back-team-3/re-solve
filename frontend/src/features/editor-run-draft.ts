"use client";
import { useEffect, useState } from "react";
import { useWorkspace, useWorkspaceApi } from "@/stores/workspace";
import { readRunInputDraft, writeRunInputDraft } from "@/services/run-input-draft";

/** 문제·회차·회원·응시 문맥별 입력을 주기적으로 저장하고 화면 이탈 시 남은 변경을 저장한다. */
export function useRunInputDraft(key: string) {
  const api = useWorkspaceApi();
  const configuration = useWorkspace((state) => state.practiceRunInputs[key]);
  const [storageError, setStorageError] = useState("");
  useEffect(() => {
    if (!api.getState().practiceRunInputs[key]) {
      const draft = readRunInputDraft(key);
      if (draft) api.getState().setPracticeRunInputs(key, draft);
    }
    let latestDraft = api.getState().practiceRunInputs[key];
    // 메모리의 초안은 저장 실패 후 남은 값일 수 있으므로 재마운트 시에도 저장을 재시도한다.
    let dirty = Boolean(latestDraft);
    const unsubscribe = api.subscribe((state, previous) => {
      const next = state.practiceRunInputs[key];
      // 로그아웃은 store를 먼저 비우므로 마지막 편집 스냅샷을 종료 저장까지 유지한다.
      if (next && next !== previous.practiceRunInputs[key]) {
        latestDraft = next;
        dirty = true;
      }
    });
    const flush = () => {
      if (!dirty || !latestDraft) return;
      const saved = writeRunInputDraft(key, latestDraft);
      if (saved) dirty = false;
      return saved;
    };
    const timer = setInterval(() => {
      const saved = flush();
      if (saved !== undefined) setStorageError(saved ? "" : "브라우저 저장에 실패했습니다. 이 화면의 입력은 유지됩니다.");
    }, 1000);
    const onHide = () => { flush(); };
    window.addEventListener("pagehide", onHide);
    return () => { flush(); unsubscribe(); clearInterval(timer); window.removeEventListener("pagehide", onHide); };
  }, [api, key]);
  return { configuration, storageError };
}
