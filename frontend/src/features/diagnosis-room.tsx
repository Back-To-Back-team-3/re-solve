"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { actionErrorMessage, diagnosisApi, isApiError, isUnauthorized } from "@/services/api";
import { useDiagnosisRefresh } from "@/services/hooks";
import { Button, Go, Loading, Modal, useLoginRedirect } from "@/components/ui";
import { compareDiagnosisSeq, diagnosisCodeError, diagnosisDraftKey, diagnosisStatusLabel } from "@/services/diagnosis";
import { useDiagnosis, useDiagnosisApi } from "@/stores/diagnosis";
import { DiagnosisEditor } from "@/features/diagnosis-editor";

export function DiagnosisRoom({ userId }: { userId: string }) {
  const { detail, activeProblem, select, buffers } = useDiagnosis();
  const store = useDiagnosisApi();
  const router = useRouter();
  const toLogin = useLoginRedirect();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState("");
  const [queryError, setQueryError] = useState("");
  const [storageError, setStorageError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now);
  const lock = useRef(false);
  const saves = useRef(new Map<string, Promise<void>>());
  const detailRequest = useRef<Promise<boolean> | null>(null);
  const mounted = useRef(true);
  const [clock, setClock] = useState(() => ({ server: detail.serverNow, received: Date.now() }));
  const clockRef = useRef(clock);
  const report = useCallback((failure: unknown) => {
    if (!mounted.current) return;
    if (isUnauthorized(failure)) { toLogin(); return; }
    setError(actionErrorMessage(failure));
  }, [toLogin]);

  async function refresh(afterCurrent = false): Promise<boolean> {
    if (detailRequest.current) {
      if (!afterCurrent) return detailRequest.current;
      await detailRequest.current;
      return refresh();
    }
    const id = store.getState().detail.diagnosisId;
    const request = diagnosisApi.getDetail(id).then((detail) => {
      if (!mounted.current) return false;
      store.getState().merge(detail);
      if (store.getState().detail === detail && Date.parse(detail.serverNow) > Date.parse(clockRef.current.server)) {
        const nextClock = { server: detail.serverNow, received: Date.now() };
        clockRef.current = nextClock;
        setClock(nextClock);
      }
      setQueryError("");
      return true;
    }).catch((failure: unknown) => {
      if (!mounted.current) return false;
      if (isUnauthorized(failure)) { toLogin(); return false; }
      setQueryError(actionErrorMessage(failure));
      return false;
    }).finally(() => { detailRequest.current = null; });
    detailRequest.current = request;
    return request;
  }

  function deadlinePassed() {
    const detail = store.getState().detail;
    return Date.now() - clockRef.current.received >= Date.parse(detail.endsAt) - Date.parse(clockRef.current.server);
  }

  async function saveOne(problemId: string) {
    await saves.current.get(problemId);
    const { detail, buffers } = store.getState();
    const buffer = buffers[problemId];
    const body = buffer?.draft;
    if (!body || compareDiagnosisSeq(body.seq, buffer.savedSeq) <= 0 || detail.status !== "IN_PROGRESS" || deadlinePassed()) return;
    const invalid = diagnosisCodeError(body.sourceCode);
    if (invalid) throw new Error(invalid);
    const request = diagnosisApi.saveDraft(detail.diagnosisId, problemId, body).then(async (result) => {
      if (!mounted.current) return;
      if (result.applied) store.getState().acknowledge(problemId, result.seq);
      else {
        // 거절 전에 시작한 폴링 응답은 복구 근거가 될 수 없다. (API 명세서 §1.5.2·§1.5.4)
        if (!await refresh(true) || compareDiagnosisSeq(store.getState().buffers[problemId].savedSeq, result.seq) < 0)
          throw new Error("서버의 최신 코드를 확인하지 못했습니다. 상태를 다시 조회한 뒤 종료해 주세요.");
      }
    });
    saves.current.set(problemId, request);
    try { await request; }
    finally { if (saves.current.get(problemId) === request) saves.current.delete(problemId); }
  }

  useEffect(() => {
    mounted.current = true;
    const { detail } = store.getState();
    for (const problem of detail.problems) {
      try { store.getState().restore(problem.problemId, localStorage.getItem(diagnosisDraftKey(userId, detail.diagnosisId, problem.problemId))); }
      catch { setStorageError("브라우저에 코드를 보관할 수 없습니다. 서버 저장 상태를 확인해 주세요."); }
    }
    const unsubscribe = store.subscribe((state, previous) => {
      for (const [id, buffer] of Object.entries(state.buffers)) {
        if (buffer === previous.buffers[id]) continue;
        try { localStorage.setItem(diagnosisDraftKey(userId, detail.diagnosisId, id), JSON.stringify(buffer)); }
        catch { setStorageError("브라우저에 코드를 보관할 수 없습니다. 서버 저장 상태를 확인해 주세요."); }
      }
    });
    setReady(true);
    return () => { mounted.current = false; unsubscribe(); };
  }, [store, userId]);

  useEffect(() => {
    if (!ready) return;
    const dirty = new Map<string, { first: number; last: number; retryAt: number }>();
    const track = () => {
      for (const [id, buffer] of Object.entries(store.getState().buffers)) {
        if (!buffer.draft || compareDiagnosisSeq(buffer.draft.seq, buffer.savedSeq) <= 0) { dirty.delete(id); continue; }
        const existing = dirty.get(id);
        dirty.set(id, { first: existing?.first ?? Date.now(), last: Date.now(), retryAt: existing?.retryAt ?? 0 });
      }
    };
    track();
    const unsubscribe = store.subscribe((state, previous) => {
      if (Object.entries(state.buffers).some(([id, buffer]) => buffer.draft !== previous.buffers[id]?.draft)) track();
    });
    const timer = setInterval(() => {
      setNow(Date.now());
      if (lock.current) return;
      for (const [id, timing] of dirty) {
        if (saves.current.has(id) || Date.now() < timing.retryAt) continue;
        if (Date.now() - timing.last < 2000 && Date.now() - timing.first < 10000) continue;
        timing.retryAt = Date.now() + 10000;
        void saveOne(id).then(() => {
          const buffer = store.getState().buffers[id];
          if (buffer?.draft && compareDiagnosisSeq(buffer.draft.seq, buffer.savedSeq) <= 0) dirty.delete(id);
          else {
            const remaining = dirty.get(id);
            if (remaining) { remaining.retryAt = 0; remaining.first = Date.now(); }
          }
        }).catch((failure) => {
          report(failure);
          if (isApiError(failure) && failure.code === "DIAGNOSIS_CLOSED") void refresh();
        });
      }
    }, 500);
    return () => { clearInterval(timer); unsubscribe(); };
    // 현재 상태는 store에서 읽고, 문항별 타이머는 편집마다 재생성하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, store]);

  useDiagnosisRefresh(refresh, detail.status === "SCORED", ready);

  async function submit(problemId: string) {
    if (lock.current || deadlinePassed() || store.getState().detail.status !== "IN_PROGRESS") return;
    const { buffers, detail } = store.getState();
    const body = buffers[problemId]?.pending ?? buffers[problemId]?.draft;
    if (!body) return;
    const invalid = diagnosisCodeError(body.sourceCode, true);
    if (invalid) { setError(invalid); return; }
    lock.current = true; setBusy(true); setError("");
    store.getState().setPending(problemId, body);
    try {
      await saves.current.get(problemId);
      const receipt = await diagnosisApi.submit(detail.diagnosisId, problemId, body);
      if (!mounted.current) return;
      store.getState().setPending(problemId, null);
      store.getState().acknowledge(problemId, body.seq);
      setMessage(`제출 #${receipt.diagnosisSubmissionId} 접수 완료. 판정은 진단 종료 후 공개됩니다.`);
      await refresh(); router.refresh();
    } catch (failure) { report(failure); if (isApiError(failure) && failure.code === "DIAGNOSIS_CLOSED") await refresh(); }
    finally { lock.current = false; if (mounted.current) setBusy(false); }
  }

  async function finish() {
    if (lock.current || deadlinePassed() || store.getState().detail.status !== "IN_PROGRESS") return;
    lock.current = true; setBusy(true); setError("");
    try {
      for (const id of Object.keys(store.getState().buffers)) await saveOne(id);
      await diagnosisApi.finish(store.getState().detail.diagnosisId);
      if (!mounted.current) return;
      store.getState().close();
      await refresh(); router.refresh();
    } catch (failure) { report(failure); await refresh(); }
    finally { lock.current = false; if (mounted.current) setBusy(false); }
  }
  const remaining = Math.max(0, Math.ceil((Date.parse(detail.endsAt) - Date.parse(clock.server) - (now - clock.received)) / 1000));
  async function flush() {
    try { for (const id of Object.keys(store.getState().buffers)) await saveOne(id); setError(""); }
    catch (failure) { report(failure); }
  }
  const session = { ready, remaining, busy, error: error || queryError, storageError, message, submit, finish, refresh, flush };

  const [confirm, setConfirm] = useState(false);
  if (!session.ready) return <Loading />;
  const open = detail.status === "IN_PROGRESS";
  const expired = open && session.remaining === 0;
  return <>
    <div className="exam-bar row wrap">
      <div><h2>알고리즘 성장 진단</h2><span>진단 #{detail.diagnosisId} · {diagnosisStatusLabel(detail.status)}</span></div>
      <div className="row wrap">
        {open && <span className="timer" aria-label="남은 시간">{Math.floor(session.remaining / 60)}:{String(session.remaining % 60).padStart(2, "0")}</span>}
        <Go href="/diagnoses">진단 목록</Go>
        {open && <Button className="primary" disabled={session.busy || expired} onClick={() => setConfirm(true)}>최종 제출 및 종료</Button>}
      </div>
    </div>
    <div className="container stack-sm">
      {session.storageError && <p role="alert" className="error-text">{session.storageError}</p>}
      {session.error && <div role="alert" className="error-text">{session.error}</div>}
      {session.message && <p role="status">{session.message}</p>}
      {expired && <p role="status">제한 시간이 끝났습니다. 서버의 자동 제출·종료 처리를 확인하고 있습니다.</p>}
      <Button className="small" onClick={() => void session.refresh()}>진단 상태 새로고침</Button>
    </div>
    {open ? <div className="exam-workspace">
      <aside className="exam-sidebar stack-sm">
        <h3>진단 문제</h3>
        {detail.problems.map((problem, index) => <button key={problem.problemId}
          className={activeProblem === problem.problemId ? "active" : ""} disabled={session.busy}
          onClick={() => { void session.flush(); select(problem.problemId); }}>
          <b>문제 {index + 1} · Lv.{problem.difficulty}</b>
          <p>{problem.lastSubmission ? diagnosisStatusLabel(problem.lastSubmission.submissionStatus) : "미제출"}</p>
          {buffers[problem.problemId]?.pending && <small>제출 응답 확인 필요</small>}
        </button>)}
      </aside>
      <DiagnosisEditor disabled={session.busy || expired} onSubmit={() => void session.submit(activeProblem)} onSave={() => void session.flush()} />
    </div> : <section className="container stack">
      <div className="card stack-sm">
        <h2>{detail.status === "SCORED" ? "진단 결과" : "채점 결과를 기다리고 있어요"}</h2>
        {detail.status === "SCORED" && detail.result ? <>
          <p>점수: {detail.result.score}</p><p>진단 결과 레벨: {detail.result.resultLevel}</p><p>적용 레벨: {detail.result.appliedLevel}</p>
          <p>재응시 결과가 낮아도 기존 레벨은 유지됩니다.</p>
        </> : <p>채점이 완료되면 결과를 갱신합니다. 페이지를 나갔다가 다시 조회할 수 있습니다.</p>}
        {detail.problems.map((problem, index) => <p key={problem.problemId}>
          문제 {index + 1}: {problem.lastSubmission ? diagnosisStatusLabel(problem.lastSubmission.submissionStatus) : "미제출"}
          {problem.lastSubmission?.submissionStatus === "FAILED" && " — 채점 장애가 발생했습니다. 재응시 가능 여부는 진단 목록에서 확인해 주세요."}
        </p>)}
        <Go href="/diagnoses">태그 레벨 확인</Go>
      </div>
    </section>}
    <Modal open={confirm && open && !expired} onOpenChange={setConfirm} title="진단을 종료할까요?"
      description="작성 중인 코드를 저장한 뒤 진단을 종료합니다. 마지막 제출 이후 변경된 코드는 서버에서 자동 제출하며, 종료 후에는 수정할 수 없습니다.">
      <div className="row wrap">
        <Button disabled={session.busy} onClick={() => setConfirm(false)}>계속 풀기</Button>
        <Button disabled={session.busy} className="primary" onClick={() => void session.finish().then(() => setConfirm(false))}>저장 후 진단 종료</Button>
      </div>
    </Modal>
  </>;
}
