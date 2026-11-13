"use client";
import { Play, RotateCcw, Send } from "lucide-react";
import { Button } from "@/components/ui";

export function EditorActions({ saved, ready, busy, busyRun, pendingSubmission, onReset, onRun, onSubmit, onTestCases }: {
  saved: string; ready: boolean; busy: boolean; busyRun: boolean; pendingSubmission: boolean;
  onTestCases?: () => void;
  onReset: () => void; onRun: () => void; onSubmit: () => void;
}) {
  return <div className="editor-actions">
    <div className="stack-sm">
      {onTestCases && <Button className="small" disabled={!ready || busy || busyRun} onClick={onTestCases}>테스트 케이스 추가하기</Button>}
      {(saved.includes("실패") || saved === "제출 완료") && (
        <span role="status" className={saved.includes("실패") ? "error-text" : ""}>{saved}</span>
      )}
    </div>
    <div className="row">
      <Button className="small" aria-label="코드 초기화" onClick={onReset}><RotateCcw size={15} /></Button>
      <Button className="small" disabled={busyRun || busy || !ready} onClick={onRun}><Play size={14} />실행</Button>
      <Button className="small primary" disabled={busy || busyRun || !ready} onClick={onSubmit}>
        <Send size={14} />{busy ? "채점 중…" : pendingSubmission ? "현재 코드로 새 제출" : "제출"}
      </Button>
    </div>
  </div>;
}
