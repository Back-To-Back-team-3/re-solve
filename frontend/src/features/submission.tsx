"use client";
import { useState } from "react";
import { useParams } from "next/navigation";
import { Badge, Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { judgeApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { SubmissionTestResult } from "@/services/types";

/** 숨김 결과에는 명세에 허용된 번호·통과 여부만 렌더링한다. */
function TestResults({ id, attempt }: { id: string; attempt: number }) {
  const resource = useResource(() => judgeApi.getTests(id, attempt), `${id}:${attempt}`);
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  return <div className="stack-sm">{resource.data?.content.map((row: SubmissionTestResult) =>
    <section className="card stack-sm" key={row.testOrder}>
      <h3>테스트 {row.testOrder}</h3>
      {"isSample" in row && row.isSample ? <>
        <p>공개 · {row.verdict ?? "판정 대기"} · {row.timeMs ?? "—"}ms · {row.memoryKb ?? "—"}KiB</p>
        <pre className="mono">입력: {JSON.stringify(row.argumentTexts, null, 2)}</pre>
        <pre className="mono">기대값: {row.expectedReturnText}</pre>
        <pre className="mono">반환값: {row.actualReturnText ?? "—"}</pre>
      </> : "passed" in row ? <p>숨김 · {row.passed === null ? "판정 대기" : row.passed ? "통과" : "실패"}</p> : null}
    </section>)}
  </div>;
}

export function SubmissionPage() {
  const { id } = useParams<{ id: string }>();
  const [selected, setSelected] = useState<number | null>(null);
  const resource = useResource(async () => {
    const [status, source, attempts] = await Promise.all([
      judgeApi.getStatus(id), judgeApi.getSource(id), judgeApi.getAttempts(id),
    ]);
    return { status, source, attempts: attempts.content };
  }, id);
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  if (!resource.data) return null;
  const { status, source, attempts } = resource.data;
  const attempt = selected ?? attempts[0]?.judgeAttempt;
  return <div className="container stack">
    <Heading title={`제출 #${id}`} action={<Button onClick={resource.retry}>결과 새로고침</Button>} />
    <Go href={`/problems/${status.problemId}`}>문제로 돌아가기</Go>
    <section className="card stack-sm">
      <Badge>{status.status}</Badge>
      {status.status === "FAILED" && <p className="error-text">채점 시스템 실패: {status.failureCode ?? "ENGINE_UNAVAILABLE"}</p>}
      {status.effectiveResult && <p>판정: {status.effectiveResult.verdict} · {status.effectiveResult.passedCount}/{status.effectiveResult.totalCount} 통과</p>}
      {status.compileMessage && <pre className="error-text">{status.compileMessage}</pre>}
      <p>{source.language} · 문제 회차 {status.problemRevisionId}</p>
      <pre className="mono">{source.sourceCode}</pre>
    </section>
    <section className="card stack-sm">
      <h2>채점 회차</h2>
      <select className="input" aria-label="채점 회차" value={attempt ?? ""}
        onChange={(event) => setSelected(Number(event.target.value))}>
        {attempts.map((item) => <option key={item.judgeAttempt} value={item.judgeAttempt}>
          {item.judgeAttempt}회차 · {item.trigger} · {item.status} · {item.verdict ?? "미확정"}
        </option>)}
      </select>
      {attempt != null && <TestResults key={`${id}:${attempt}`} id={id} attempt={attempt} />}
    </section>
  </div>;
}
