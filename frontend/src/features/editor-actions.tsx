"use client";
import { Play, RotateCcw, Save, Send } from "lucide-react";
import { Button } from "@/components/ui";

export function EditorActions({ saved, ready, busy, busyRun, pendingSubmission, onSave, onReset, onRun, onSubmit }: {
  saved: string; ready: boolean; busy: boolean; busyRun: boolean; pendingSubmission: boolean;
  onSave: () => void; onReset: () => void; onRun: () => void; onSubmit: () => void;
}) {
  return <div className="editor-actions">
    <span role="status" className={saved.startsWith("저장 실패") ? "error-text" : ""}>{saved}</span>
    <div className="row">
      <Button className="small" aria-label="코드 저장" onClick={onSave}><Save size={15} /></Button>
      <Button className="small" aria-label="코드 초기화" onClick={onReset}><RotateCcw size={15} /></Button>
      <Button className="small" disabled={busyRun || busy || !ready} onClick={onRun}><Play size={14} />실행</Button>
      <Button className="small primary" disabled={busy || busyRun || !ready} onClick={onSubmit}>
        <Send size={14} />{busy ? "채점 중…" : pendingSubmission ? "현재 코드로 새 제출" : "제출"}
      </Button>
    </div>
  </div>;
}
