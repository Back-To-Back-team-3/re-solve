"use client";
import { useEffect, useLayoutEffect, useRef } from "react";
import { Badge, Empty } from "@/components/ui";
import { judgeStatusLabel, labelOf, verdictLabel } from "@/services/labels";
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
            ? `${v} ${labelOf(verdictLabel, v)}`
            : labelOf(judgeStatusLabel, result.status)}
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
export function RunResult({ result, expectations = [] }: { result: RunResponse; expectations?: (string | null)[] }) {
  if (result.compileMessage) {
    return <pre className="error-text">{result.compileMessage}</pre>;
  }
  return (
    <div className="stack-sm" aria-label="테스트별 실행 결과">
      {result.results.map((item, index) => {
        const expected = item.source === "CUSTOM" && item.index !== undefined ? expectations[item.index] : null;
        return (
          <article className="card stack-sm min-w-0" key={index} aria-label={`테스트 ${index + 1}`}>
            <div className="row between wrap">
              <h4>테스트 {index + 1} <small className="muted">· {item.source === "SAMPLE" ? "공개 예제" : "직접 입력"}</small></h4>
              <Badge tone={item.verdict === "AC" ? "success" : item.verdict ? "error" : ""}>
                {item.verdict === "AC" ? "통과" : item.verdict ? labelOf(verdictLabel, item.verdict) : "실행 완료"}
              </Badge>
            </div>
            <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">{expected != null
              ? `사용자 기대값: ${expected}` : `기대값: ${item.expectedReturnText ?? "없음 (직접 입력)"}`}</pre>
            <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">실제 반환값: {item.actualReturnText ?? "—"}</pre>
            {expected != null && <small className="muted">사용자 기대값은 참고용이며 자동 판정하지 않습니다.</small>}
            <p className={item.verdict && item.verdict !== "AC" ? "error-text" : ""}>
              실행 결과: {item.verdict === "AC" ? "기대값과 일치합니다." : item.verdict === "WA"
                ? "반환값이 기대값과 다릅니다." : item.verdict ? labelOf(verdictLabel, item.verdict) : "직접 입력 실행을 완료했습니다."}
            </p>
            <p className="muted">실행 시간: {item.timeMs ?? "—"}ms · 메모리: {item.memoryKb ?? "—"}KiB</p>
            {item.stdout && <div className="stack-sm">
              <h5>디버그 출력</h5>
              <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">{item.stdout}</pre>
            </div>}
          </article>
        );
      })}
    </div>
  );
}


export function EditorResultPanel({ busy, showRun, busyRun, queryError, judgeResult, runError, runResult, runExpectations }: {
  busy: boolean; showRun: boolean; busyRun: boolean; queryError: string | null;
  runExpectations?: (string | null)[];
  judgeResult: JudgeResult | null; runError: string | null; runResult: RunResponse | null;
}) {
  if (busy) return <div className="row"><Badge tone="pending">채점 중…</Badge></div>;
  if (showRun) {
    if (busyRun) return <p className="muted">실행 중…</p>;
    if (runError) return <p className="error-text">{runError}</p>;
    if (runResult) return <RunResult result={runResult} expectations={runExpectations} />;
  } else {
    if (queryError) return <p className="error-text">{queryError}</p>;
    if (judgeResult) return <JudgeResultView result={judgeResult} />;
  }
  return <Empty title="코드를 실행해 보세요" description="공개 예제 실행 또는 전체 제출을 선택할 수 있어요." />;
}
