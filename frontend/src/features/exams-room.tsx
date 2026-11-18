"use client";
import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { Timer } from "lucide-react";
import { useResource } from "@/services/hooks";
import { contestApi, judgeApi } from "@/services/api";
import {
  Button,
  Empty,
  ErrorState,
  Go,
  Loading,
  Modal,
  Badge,
} from "@/components/ui";
import { useWorkspace } from "@/stores/workspace";
import { ProblemDescription } from "./problem";
import { toEditorExamProblem } from "@/services/exam-problem";
import { CodeEditor } from "./editor";

export function ExamRoom({ id }: { id: string }) {
  const r = useResource(() => contestApi.getExamProblems(id), id);
  const runtimes = useResource(judgeApi.getLanguages, "exam-languages");
  const router = useRouter();
  const member = useWorkspace((s) => s.member);
  const active = useWorkspace((s) => s.activeProblems[id]);
  const setActive = useWorkspace((s) => s.setActive);

  const [now, setNow] = useState(() => Date.now());
  const [offset, setOffset] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const expired = useRef(false);

  useEffect(() => {
    if (!r.data) return;
    setOffset(new Date(r.data.serverNow).getTime() - Date.now());
  }, [r.data]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);

  const personalEndsAt = r.data?.personalEndsAt ?? "";
  const remaining = personalEndsAt
    ? Math.max(
        0,
        Math.floor(
          (new Date(personalEndsAt).getTime() - (now + offset)) / 1000,
        ),
      )
    : 0;

  useEffect(() => {
    if (!personalEndsAt || !r.data) return;
    if (remaining === 0 && !expired.current) {
      expired.current = true;
      contestApi
        .finishExam(id)
        .catch(() => {})
        .finally(() => router.replace(`/exams/${id}/result`));
    }
  }, [remaining, personalEndsAt, r.data, id, router]);

  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error.message} retry={r.retry} />
      </div>
    );
  if (!r.data || r.data.problems.length === 0)
    return (
      <div className="container">
        <Empty
          title="대기실에서 응시를 시작해 주세요"
          description="시험 참가와 로그인 상태를 확인합니다."
          action={<Go href={`/exams/${id}`}>대기실로 이동</Go>}
        />
      </div>
    );

  const uiProblems = r.data.problems.map(toEditorExamProblem);
  const activeProblemId = uiProblems.some((p) => p.id === active)
    ? active
    : uiProblems[0].id;
  const problem = uiProblems.find((p) => p.id === activeProblemId)!;
  const userId = member?.memberId ?? "";

  async function finish() {
    setFinishing(true);
    try {
      await contestApi.finishExam(id);
    } catch {}
    router.push(`/exams/${id}/result`);
    setFinishing(false);
  }

  return (
    <>
      <div className="exam-bar">
        <div className="row">
          <Badge tone="accent">실전 모의</Badge>
          <h3>모의 코딩테스트</h3>
        </div>
        <div className="row wrap">
          <span className="timer" aria-label="남은 시간">
            <Timer size={18} />{" "}
            {String(Math.floor(remaining / 60)).padStart(2, "0")}:
            {String(remaining % 60).padStart(2, "0")}
          </span>
          <Button className="primary" onClick={() => setConfirm(true)}>
            응시 종료
          </Button>
        </div>
      </div>
      {runtimes.loading && <p className="container muted" role="status">실행 언어를 확인하고 있습니다. 코드 작성·저장·제출은 계속할 수 있습니다.</p>}
      {runtimes.error && <div className="container stack-sm" role="alert">
        <p className="error-text">{runtimes.error.message}</p>
        <p>코드 작성·저장·제출·종료는 계속할 수 있습니다. 실행 언어를 확인한 뒤 Run을 다시 사용할 수 있습니다.</p>
        <Button onClick={runtimes.retry}>실행 언어 다시 조회</Button>
      </div>}
      <div className="exam-workspace">
        <aside className="exam-sidebar stack-sm">
          <h3>시험 문제</h3>
          <small>{r.data.problems.length}문제 · 자동 저장</small>
          {r.data.problems.map((p, i) => (
            <button
              className={activeProblemId === p.problemId ? "active" : ""}
              key={p.problemId}
              onClick={() => {
                window.dispatchEvent(new Event("resolve:flush"));
                setActive(id, p.problemId);
              }}
            >
              <b>문제 {i + 1}</b>
              <p>{p.title}</p>
            </button>
          ))}
          <Go className="small" href={`/exams/${id}`}>
            대기실
          </Go>
        </aside>
        <div className="workspace">
          <ProblemDescription key={problem.id} problem={problem} exam examId={id} />
          <CodeEditor problem={problem} userId={userId} examId={id}
            runDisabled={runtimes.loading || !!runtimes.error}
            availableLanguages={runtimes.data?.content.filter((item) => item.enabled).map((item) => item.language)} />
        </div>
      </div>
      <Modal
        open={confirm}
        onOpenChange={setConfirm}
        title="응시를 종료할까요?"
        description="종료하면 더 이상 코드를 편집할 수 없습니다. 각 문제의 마지막 저장 코드를 최종 제출합니다."
      >
        <div className="row">
          <Button onClick={() => setConfirm(false)}>계속 풀기</Button>
          <Button disabled={finishing} className="primary" onClick={finish}>
            전체 제출 및 종료
          </Button>
        </div>
      </Modal>
    </>
  );
}
