"use client";
import { useEffect, useLayoutEffect, useRef } from "react";
import { Badge, Empty } from "@/components/ui";
import { labels } from "@/services/mock-api";
import { judgeApi } from "@/services/api";
import { usePollUntil } from "@/services/hooks";
import type { JudgeResult, RunResponse } from "@/services/types";

export function JudgePoll({
  submissionId,
  onDone,
}: {
  submissionId: string;
  onDone: (result: JudgeResult | null, error: string | null) => void;
}) {
  const onDoneRef = useRef(onDone);
  useLayoutEffect(() => {
    onDoneRef.current = onDone;
  });
  const { data, loading, error, polling } = usePollUntil(
    (signal) => judgeApi.getStatus(submissionId, signal),
    (d) => d.status === "COMPLETED" || d.status === "FAILED",
    { intervalMs: 2000, subsequentIntervalMs: 5000, timeoutMs: 60000 },
  );
  useEffect(() => {
    if (loading || polling) return;
    if (error) { onDoneRef.current(null, error.message); return; }
    if (data) onDoneRef.current(data, null);
  }, [loading, polling, data, error]);
  return null;
}
const VERDICT_LABELS: Record<string, string> = {
  AC: "정답", WA: "오답", TLE: "시간 초과",
  MLE: "메모리 초과", RE: "런타임 에러", CE: "컴파일 에러",
};
function verdictTone(verdict: string | null): string {
  if (!verdict) return "pending";
  if (verdict === "AC") return "success";
  if (verdict === "WA" || verdict === "RE") return "error";
  return "warning";
}
export function JudgeResultView({ result }: { result: JudgeResult }) {
  const v = result.effectiveResult?.verdict ?? null;
  const r = result.effectiveResult;
  return (
    <div className="stack-sm">
      <div className="row">
        <Badge tone={verdictTone(v)}>
          {v
            ? `${v} ${VERDICT_LABELS[v] ?? v}`
            : labels[result.status]}
        </Badge>
        {r && (
          <span>
            {r.passedCount} / {r.totalCount} 통과
            {r.maxTimeMs != null && ` · ${r.maxTimeMs}ms`}
            {r.maxMemoryKb != null &&
              ` · ${Math.round(r.maxMemoryKb / 1024)}MB`}
          </span>
        )}
      </div>
      {result.status === "FAILED" && <p className="error-text">채점 시스템 실패: {result.failureCode ?? "ENGINE_UNAVAILABLE"}</p>}
      {result.compileMessage && (
        <pre className="error-text">{result.compileMessage}</pre>
      )}
    </div>
  );
}
export function RunResult({ result }: { result: RunResponse }) {
  if (result.compileMessage) {
    return <pre className="error-text">{result.compileMessage}</pre>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>결과</th>
            <th>반환값·기대값</th>
            <th>성능·디버그 출력</th>
          </tr>
        </thead>
        <tbody>
          {result.results.map((t, i) => (
            <tr key={i}>
              <td>{i + 1}</td>
              <td>
                <Badge
                  tone={
                    t.verdict === "AC"
                      ? "success"
                      : t.verdict
                        ? "error"
                        : ""
                  }
                >
                  {t.verdict === "AC"
                    ? "통과"
                    : t.verdict
                      ? labels[t.verdict]
                      : "실행"}
                </Badge>
              </td>
              <td><pre className="mono">반환값: {t.actualReturnText ?? "—"}</pre>
                <pre className="mono">기대값: {t.expectedReturnText ?? "없음 (직접 입력)"}</pre></td>
              <td><p>{t.timeMs ?? "—"}ms · {t.memoryKb ?? "—"}KiB</p><pre className="mono">{t.stdout}</pre></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}


export function EditorResultPanel({ busy, showRun, busyRun, queryError, judgeResult, runError, runResult }: {
  busy: boolean; showRun: boolean; busyRun: boolean; queryError: string | null;
  judgeResult: JudgeResult | null; runError: string | null; runResult: RunResponse | null;
}) {
  if (busy) return <div className="row"><Badge tone="pending">채점 중…</Badge></div>;
  if (showRun) {
    if (busyRun) return <p className="muted">실행 중…</p>;
    if (runError) return <p className="error-text">{runError}</p>;
    if (runResult) return <RunResult result={runResult} />;
  } else {
    if (queryError) return <p className="error-text">{queryError}</p>;
    if (judgeResult) return <JudgeResultView result={judgeResult} />;
  }
  return <Empty title="코드를 실행해 보세요" description="공개 예제 실행 또는 전체 제출을 선택할 수 있어요." />;
}
