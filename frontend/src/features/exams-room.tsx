"use client";
import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { Timer } from "lucide-react";
import { api, remainingSeconds } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import {
  Button,
  Empty,
  ErrorState,
  Go,
  Loading,
  Modal,
  NotFound,
  Badge,
  useAction,
} from "@/components/ui";
import {
  useWorkspace,
  useWorkspaceApi,
  examDrafts,
  readDraft,
  type Draft,
} from "@/stores/workspace";
import { ProblemDescription } from "./problem";
import { CodeEditor } from "./editor";
import { useCatalog } from "@/services/catalog";

export function ExamRoom({ id }: { id: string }) {
  const { problems } = useCatalog();
  const r = useResource(
      async () => ({
        exam: await api.exam(id),
        session: await api.session(id),
        user: await api.currentUser(),
      }),
      id,
    ),
    router = useRouter(),
    action = useAction(),
    [now, setNow] = useState(Date.now),
    [confirm, setConfirm] = useState(false),
    [finishing, setFinishing] = useState(false),
    expired = useRef(false);
  const store = useWorkspaceApi();
  const [endError, setEndError] = useState("");
  function collected() {
    const all: Record<string, Draft> = {};
    try {
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith("resolve:draft:")) {
          const d = readDraft(key.slice("resolve:draft:".length));
          if (d) all[key.slice("resolve:draft:".length)] = d;
        }
      }
    } catch {}
    Object.assign(all, store.getState().drafts);
    return examDrafts(all, r.data?.user?.id || "", id);
  }
  const collectRef = useRef(collected);
  useEffect(() => {
    collectRef.current = collected;
  });
  const active = useWorkspace((s) => s.activeProblems[id]),
    setActive = useWorkspace((s) => s.setActive);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);
  const session = r.data?.session;
  useEffect(() => {
    if (session?.finishedAt) router.replace(`/exams/${id}/result`);
    else if (
      session &&
      remainingSeconds(session.endsAt, now) === 0 &&
      !expired.current
    ) {
      expired.current = true;
      window.dispatchEvent(new Event("resolve:flush"));
      api
        .finishExam(id, collectRef.current())
        .then(() => router.replace(`/exams/${id}/result`))
        .catch(() => {
          setEndError(
            "응시 종료를 저장하지 못했습니다. 저장소 공간을 확보한 뒤 다시 시도해 주세요.",
          );
        });
    }
  }, [session, now, id, router]);
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
  const e = r.data?.exam;
  if (!e) return <NotFound what="시험" />;
  if (!session || !r.data?.user)
    return (
      <div className="container">
        <Empty
          title="대기실에서 응시를 시작해 주세요"
          description="시험 참가와 로그인 상태를 확인합니다."
          action={<Go href={`/exams/${id}`}>대기실로 이동</Go>}
        />
      </div>
    );
  if (endError)
    return (
      <div className="container">
        <ErrorState
          message={endError}
          retry={() => {
            setEndError("");
            expired.current = false;
          }}
        />
      </div>
    );
  if (session.finishedAt || remainingSeconds(session.endsAt, now) === 0)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  const problem = problems.find(
      (p) =>
        p.id === (e.problemIds.includes(active) ? active : e.problemIds[0]),
    )!,
    seconds = remainingSeconds(session.endsAt, now);
  async function finish() {
    setFinishing(true);
    window.dispatchEvent(new Event("resolve:flush"));
    await action(async () => {
      await api.finishExam(id, collected());
      router.push(`/exams/${id}/result`);
    });
    setFinishing(false);
  }
  return (
    <>
      <div className="exam-bar">
        <div className="row">
          <Badge tone="accent">실전 모의</Badge>
          <h3>{e.title}</h3>
        </div>
        <div className="row wrap">
          <span className="timer" aria-label="남은 시간">
            <Timer size={18} />{" "}
            {String(Math.floor(seconds / 60)).padStart(2, "0")}:
            {String(seconds % 60).padStart(2, "0")}
          </span>
          <Button
            className="small"
            onClick={() =>
              action(() => api.expireExam(id), "타이머 만료를 재현합니다.")
            }
          >
            데모: 시간 만료
          </Button>
          <Button className="primary" onClick={() => setConfirm(true)}>
            응시 종료
          </Button>
        </div>
      </div>
      <div className="exam-workspace">
        <aside className="exam-sidebar stack-sm">
          <h3>시험 문제</h3>
          <small>{e.problemIds.length}문제 · 자동 저장</small>
          {e.problemIds.map((pid, i) => (
            <button
              className={problem.id === pid ? "active" : ""}
              key={pid}
              onClick={() => {
                window.dispatchEvent(new Event("resolve:flush"));
                setActive(id, pid);
              }}
            >
              <b>문제 {i + 1}</b>
              <p>{problems.find((p) => p.id === pid)?.title}</p>
            </button>
          ))}
          <Go className="small" href={`/exams/${id}`}>
            대기실
          </Go>
        </aside>
        <div className="workspace">
          <ProblemDescription key={problem.id} problem={problem} exam />
          <CodeEditor problem={problem} userId={r.data.user.id} examId={id} />
        </div>
      </div>
      <Modal
        open={confirm}
        onOpenChange={setConfirm}
        title="응시를 종료할까요?"
        description="종료하면 더 이상 코드를 편집할 수 없습니다. 문제별 가장 최근에 저장한 언어의 코드를 최종 제출하고 모의 결과를 기록합니다."
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
