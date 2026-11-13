"use client";
import { useRef, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { adminJudgeApi } from "@/services/api";
import { actionMessage } from "@/services/problem-authoring";
import { useResource } from "@/services/hooks";

function JobDetail({ id }: { id: string }) {
  const router = useRouter();
  const resource = useResource(() => adminJudgeApi.getDetail(id), id);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const locked = useRef(false);
  const pending = useRef<{ reason: string; key: string } | null>(null);
  async function recover(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked.current || !reason.trim()) return;
    locked.current = true; setBusy(true); setError(""); setMessage("");
    const normalized = reason.trim();
    if (pending.current?.reason !== normalized) pending.current = { reason: normalized, key: crypto.randomUUID() };
    try {
      await adminJudgeApi.recover(id, pending.current.reason, pending.current.key);
      pending.current = null;
      setMessage("복구 요청이 접수됐습니다. 같은 채점 회차의 최신 상태를 확인해 주세요.");
      resource.retry(); router.refresh();
    } catch (failure) {
      setError(actionMessage(failure));
      // 정상 완료와 경합했을 수 있으므로 오류를 남긴 채 최신 상태를 확인한다.
      resource.retry();
    } finally { locked.current = false; setBusy(false); }
  }
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  const job = resource.data;
  if (!job) return null;
  const terminal = job.status === "SUCCEEDED" || job.status === "FAILED";
  return <div className="container stack">
    <Heading title={`채점 작업 ${job.judgeJobId}`} action={<Go href="/admin/judge-jobs">작업 목록</Go>} />
    <Button onClick={resource.retry}>작업 상태 새로고침</Button>
    <section className="card stack-sm">
      <h2>작업 상태: {job.status}</h2><p>제출 {job.submissionId} · 채점 {job.judgeAttempt}회차 · 실행 {job.tryCount}회</p>
      <p>처리 기한: {job.deadlineAt}</p><p>임대 만료: {job.leaseUntil ?? "없음"}</p>
      <p>다음 실행: {job.nextRunAt ?? "없음"}</p><p>마지막 오류: {job.lastError ?? "없음"}</p>
    </section>
    <section className="card stack-sm"><h2>실행 이력</h2>
      {!job.runs.length && <p>실행 이력이 없습니다.</p>}
      {job.runs.map((run, index) => <div key={`${run.tryCount}:${index}`}>
        <p>실행 {run.tryCount}회: {run.result}</p><p>{run.startedAt} → {run.finishedAt ?? "진행 중"}</p><p>{run.errorMessage ?? "오류 없음"}</p>
      </div>)}
    </section>
    <section className="card stack-sm"><h2>테스트 토큰 상태</h2>
      {!job.testResults.length && <p>테스트 실행 정보가 없습니다.</p>}
      {job.testResults.map((test) => <p key={test.testOrder}>테스트 {test.testOrder}: {test.tokenStatus} · 판정 {test.verdict ?? "없음"} · 토큰 {test.judge0Token ?? "없음"}</p>)}
    </section>
    <section className="card stack-sm"><h2>진행 작업 복구</h2>
      <p>같은 채점 회차를 재개합니다. 시도 상한·처리 기한은 서버에서 확인합니다.</p>
      {terminal ? <p>종결된 작업은 복구할 수 없습니다.</p> : <form className="stack-sm" onSubmit={recover}>
        <label className="field">복구 사유<textarea className="input" required maxLength={500} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} /></label>
        <Button type="submit" disabled={busy || !reason.trim()}>{busy ? "복구 요청 중" : "복구 요청"}</Button>
      </form>}
      {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    </section>
  </div>;
}

export function AdminJudgeDetailPage() {
  const { id } = useParams<{ id: string }>();
  return <JobDetail key={id} id={id} />;
}
