"use client";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui";
import type { PracticeSubmissionAttempt, PracticeSubmissionRequest } from "@/stores/workspace";
import { expiredPracticeRequestMessage, isPracticeRequestExpired, practiceRequestTtlMs } from "@/services/practice-request";

/** 접수 조회와 미접수 재요청을 별도로 안내한다. (API 명세서 §3.1.2·§3.1.3) */
export function EditorSubmissionStatus({ accepted, selected, pending, error, disabled, paused, onSelect, onResume, onRetry }: {
  accepted: PracticeSubmissionAttempt[]; selected: PracticeSubmissionAttempt | undefined;
  pending: PracticeSubmissionRequest | undefined; error: string | null; disabled: boolean; paused: boolean;
  onSelect: (id: string) => void; onResume: () => void; onRetry: () => void;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    setNow(Date.now());
    if (!pending) return;
    const remaining = pending.createdAt + practiceRequestTtlMs - Date.now();
    if (remaining <= 0) return;
    const timer = setTimeout(() => setNow(Date.now()), remaining);
    return () => clearTimeout(timer);
  }, [pending, error]);
  const expired = pending && isPracticeRequestExpired(pending.createdAt, now);
  return <div className="stack-sm">
    {error && <p className="error-text" role="alert">{error}</p>}
    {accepted.length > 1 && <label className="field">접수된 제출<select aria-label="접수된 제출" className="input"
      disabled={disabled} value={selected?.submissionId ?? ""} onChange={(event) => onSelect(event.target.value)}>
      {accepted.map((item) => <option key={item.submissionId} value={item.submissionId}>제출 #{item.submissionId}</option>)}
    </select></label>}
    {selected && <>
      <p className="muted">접수된 제출 #{selected.submissionId}</p>
      {!selected.result && paused && <Button className="small" disabled={disabled} onClick={onResume}>채점 상태 다시 조회</Button>}
    </>}
    {pending && <>
      <p className={expired ? "error-text" : "muted"}>{expired ? expiredPracticeRequestMessage : "재요청은 원래 제출한 코드와 요청 키를 유지합니다."}</p>
      <Button className="small" disabled={disabled || expired} onClick={onRetry}>같은 제출 재요청</Button>
    </>}
  </div>;
}
