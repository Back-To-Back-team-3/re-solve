"use client";
import { useState, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { Clock, Users, Plus, Timer, Flag } from "lucide-react";
import { api, labels, remainingSeconds } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import type { Exam } from "@/services/types";
import { useCatalog } from "@/services/catalog";
import {
  Badge,
  Button,
  Empty,
  ErrorState,
  Go,
  Heading,
  Loading,
  Modal,
  NotFound,
  Section,
  Tabs,
  date,
  useAction,
} from "@/components/ui";
import { Pet } from "@/components/pet";
import {
  useWorkspace,
  useWorkspaceApi,
  examDrafts,
  readDraft,
  type Draft,
} from "@/stores/workspace";
import { ProblemTable, useQuery, pageSlice, Pager } from "./problem-list";
import { ProblemDescription } from "./problem";
import { CodeEditor } from "./editor";
export function ExamsPage({ contest = false }: { contest?: boolean }) {
  const r = useResource(
      () => api.exams(contest ? "contest" : "exam"),
      String(contest),
    ),
    { params, change } = useQuery();
  const appliedKeyword = params.get("keyword") || "";
  const [search, setSearch] = useState(appliedKeyword);
  useEffect(() => setSearch(appliedKeyword), [appliedKeyword]);
  const status = params.get("status") || "all";
  const matching = (r.data || []).filter(
    (e) =>
      (status === "all" || e.status === status) &&
      e.title.includes(appliedKeyword),
  );
  matching.sort((a, b) =>
    params.get("sort") === "participants"
      ? b.participants.length - a.participants.length
      : a.startsAt - b.startsAt,
  );
  const paged = pageSlice(matching, params.get("page")),
    items = paged.items;
  return (
    <div className="container stack">
      <Heading
        title={
          contest ? "누구나 도전하는 공개대회" : "실전처럼, 모의 코딩테스트"
        }
        eyebrow={contest ? "OPEN CHALLENGE" : "MOCK EXAM"}
        description="시간 안에 생각을 코드로. 꾸준한 연습을 실전 감각으로 바꿔 보세요."
        action={
          !contest && (
            <Go className="primary" href="/exams/new">
              <Plus size={18} />
              시험 만들기
            </Go>
          )
        }
      />
      <Tabs
        value={status}
        onChange={(s) => change({ status: s, page: "0" })}
        items={["all", "recruiting", "scheduled", "running", "ended"].map(
          (s) => ({ id: s, label: s === "all" ? "전체" : labels[s] }),
        )}
      />
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          change({ keyword: search, page: "0" });
        }}
      >
        <input
          className="input search"
          aria-label="시험 검색"
          value={search}
          placeholder="시험 이름으로 검색하세요"
          onChange={(e) => setSearch(e.target.value)}
        />
        <select
          className="input"
          aria-label="시험 정렬"
          value={params.get("sort") || ""}
          onChange={(e) => change({ sort: e.target.value, page: "0" })}
        >
          <option value="">일정순</option>
          <option value="participants">참가 인원순</option>
        </select>
        <Button>검색</Button>
      </form>
      {r.loading ? (
        <Loading />
      ) : r.error ? (
        <ErrorState message={r.error} retry={r.retry} />
      ) : items.length ? (
        <div className="grid-3">
          {items.map((e) => (
            <article key={e.id} className="card exam-card">
              <div className="row between">
                <Badge
                  tone={
                    e.status === "running"
                      ? "success"
                      : e.status === "ended"
                        ? ""
                        : "accent"
                  }
                >
                  {labels[e.status]}
                </Badge>
                <Flag size={24} />
              </div>
              <h3>{e.title}</h3>
              <p className="muted">{e.description}</p>
              <p>{date(e.startsAt)}</p>
              <div className="row wrap">
                <span className="row">
                  <Clock size={16} />
                  {e.duration}분
                </span>
                <span>{e.problemIds.length}문제</span>
                <span className="row">
                  <Users size={16} />
                  {e.participants.length}명
                </span>
              </div>
              <Go
                className={`actions ${e.status === "ended" ? "" : "primary"}`}
                href={`/${contest ? "contests" : "exams"}/${e.id}`}
              >
                {e.status === "ended" ? "결과 살펴보기" : "자세히 보기"}
              </Go>
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title="이 상태의 시험이 없어요"
          description="다른 상태를 선택하거나 새로운 모의 코테를 만들어 보세요."
        />
      )}
      <Pager {...paged} onChange={(page) => change({ page: String(page) })} />
    </div>
  );
}
export function ExamDetail({
  id,
  contest = false,
}: {
  id: string;
  contest?: boolean;
}) {
  const { problems } = useCatalog();
  const r = useResource(
      async () => ({
        exam: await api.exam(id),
        user: await api.currentUser(),
        session: await api.session(id),
      }),
      id,
    ),
    action = useAction(),
    router = useRouter();
  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error} retry={r.retry} />
      </div>
    );
  const e = r.data?.exam;
  if (!e) return <NotFound what="시험" />;
  const joined = e.participants.includes(r.data?.user?.id || "");
  return (
    <div className="container stack">
      <Go className="small" href={contest ? "/contests" : "/exams"}>
        ← 목록으로
      </Go>
      <Heading
        title={e.title}
        eyebrow="READY TO CHALLENGE"
        description={e.description}
        action={<Badge tone="accent">{labels[e.status]}</Badge>}
      />
      <div className="detail-grid">
        <div className="stack">
          <Section title="시험 안내">
            <div className="stack">
              <div className="row">
                <Pet level={3} />
                <div>
                  <h3>집중하는 시간, 성장하는 실력</h3>
                  <p className="muted">
                    모든 실행·채점 결과는 미리 정한 시나리오에 따른 목업입니다.
                  </p>
                </div>
              </div>
              <p>{e.description}</p>
              <div className="inset stack-sm">
                <p>일시: {date(e.startsAt)}</p>
                <p>제한 시간: {e.duration}분</p>
                <p>참가 조건: {e.condition || "누구나 참가할 수 있어요."}</p>
                <p>
                  공개 범위: {e.visibility === "public" ? "공개" : "비공개"}
                </p>
              </div>
              <p>
                Java, Python, C++을 사용할 수 있습니다. 응시를 시작한 시각부터
                제한 시간이 적용됩니다.
              </p>
            </div>
          </Section>
          <Section title="출제 문제">
            <ProblemTable
              items={problems.filter((p) => e.problemIds.includes(p.id))}
            />
          </Section>
        </div>
        <Section title="대기실">
          <div className="stack">
            <div className="row">
              <Users />
              <b>{e.participants.length}명 참가</b>
            </div>
            <p className="muted">
              예정된 시험도 데모에서는 바로 입장하여 흐름을 확인할 수 있습니다.
            </p>
            {e.status === "ended" || r.data?.session?.finishedAt ? (
              <Go className="primary" href={`/exams/${id}/result`}>
                결과 확인
              </Go>
            ) : (
              <>
                <Button
                  onClick={() =>
                    action(
                      () => api.joinExam(id),
                      joined ? "참가를 취소했습니다." : "참가 신청했습니다.",
                    )
                  }
                >
                  {joined ? "참가 취소" : "참가 신청"}
                </Button>
                <Button
                  className="primary"
                  onClick={() =>
                    action(async () => {
                      const s = await api.startExam(id);
                      router.push(
                        `/exams/${id}/${s.finishedAt ? "result" : "room"}`,
                      );
                    })
                  }
                >
                  {r.data?.session ? "이어서 응시" : "데모 응시 시작"}
                </Button>
              </>
            )}
            <small>시험 중 작성한 코드는 자동 저장됩니다.</small>
          </div>
        </Section>
      </div>
    </div>
  );
}
export function ExamCreate() {
  const { problems } = useCatalog();
  const router = useRouter(),
    action = useAction(),
    [selected, setSelected] = useState<string[]>(["1001"]),
    [busy, setBusy] = useState(false);
  return (
    <div className="container">
      <Heading
        title="모의 코테 만들기"
        eyebrow="NEW CHALLENGE"
        description="함께 도전할 문제와 시간을 정해 보세요."
      />
      <form
        className="card form-card stack"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          const f = new FormData(e.currentTarget);
          await action(async () => {
            const exam = await api.createExam({
              kind: "exam",
              title: String(f.get("title")).trim(),
              description: String(f.get("description")).trim(),
              duration: Number(f.get("duration")),
              startsAt: new Date(String(f.get("startsAt"))).getTime(),
              problemIds: selected,
              visibility: f.get("visibility") as Exam["visibility"],
              condition: String(f.get("condition")),
            });
            router.push(`/exams/${exam.id}`);
          }, "모의 코테를 만들었습니다.");
          setBusy(false);
        }}
      >
        <label className="field">
          시험 이름
          <input
            className="input"
            name="title"
            required
            minLength={2}
            maxLength={60}
          />
        </label>
        <label className="field">
          시험 소개
          <textarea
            className="input"
            name="description"
            required
            minLength={10}
            maxLength={1000}
          />
        </label>
        <div className="form-grid">
          <label className="field">
            시작 일시
            <input
              className="input"
              name="startsAt"
              type="datetime-local"
              required
            />
          </label>
          <label className="field">
            제한 시간 (분)
            <input
              className="input"
              name="duration"
              type="number"
              defaultValue={120}
              min={1}
              max={300}
              required
            />
          </label>
          <label className="field">
            공개 범위
            <select className="input" name="visibility">
              <option value="public">공개</option>
              <option value="private">비공개</option>
            </select>
          </label>
          <label className="field">
            참가 조건
            <input
              className="input"
              name="condition"
              placeholder="누구나 참여 가능"
            />
          </label>
        </div>
        <fieldset className="stack-sm">
          <legend>문제 선택 · {selected.length}개</legend>
          {problems.map((p) => (
            <label className="checkbox-row" key={p.id}>
              <input
                type="checkbox"
                checked={selected.includes(p.id)}
                onChange={(e) =>
                  setSelected(
                    e.target.checked
                      ? [...selected, p.id]
                      : selected.filter((id) => id !== p.id),
                  )
                }
              />
              <span className="grow">
                {p.id}. {p.title}
              </span>
              <Badge>Lv.{p.difficulty}</Badge>
            </label>
          ))}
          {!selected.length && (
            <p className="error-text">문제를 1개 이상 선택해 주세요.</p>
          )}
        </fieldset>
        <div className="row">
          <Go href="/exams">취소</Go>
          <Button className="primary grow" disabled={busy || !selected.length}>
            시험 생성
          </Button>
        </div>
      </form>
    </div>
  );
}
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
        <ErrorState message={r.error} retry={r.retry} />
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
export function ExamResult({ id }: { id: string }) {
  const { problems } = useCatalog();
  const r = useResource(
    async () => ({
      exam: await api.exam(id),
      session: await api.session(id),
      submissions: await api.submissions(undefined, id),
    }),
    id,
  );
  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error} retry={r.retry} />
      </div>
    );
  const e = r.data?.exam;
  if (!e) return <NotFound what="시험" />;
  const rows = e.problemIds.map((pid) => ({
      problem: problems.find((p) => p.id === pid)!,
      submission: r.data!.submissions.find(
        (s) => s.problemId === pid && s.kind === "submit",
      ),
    })),
    correct = rows.filter((x) => x.submission?.verdict === "AC").length;
  return (
    <div className="container stack">
      <Heading
        title="도전의 기록"
        eyebrow="CHALLENGE COMPLETE"
        description={e.title}
        action={<Badge tone="warning">모의 결과</Badge>}
      />
      <div className="card hero">
        <Pet level={correct ? 3 : 1} size={120} />
        <div className="grow">
          <h2>한 번의 도전이, 다음 성장의 시작!</h2>
          <p>
            {r.data?.session
              ? "응시 기록을 확인하고 다시 연습해 보세요."
              : "아직 이 사용자의 응시 기록이 없습니다."}
          </p>
        </div>
        <div>
          <p className="score">
            {Math.round((correct / rows.length) * 100)}
            <small>/100</small>
          </p>
          <p>
            {correct} / {rows.length}문제 정답
          </p>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>문제</th>
              <th>결과</th>
              <th>통과 테스트</th>
              <th>실행 시간</th>
              <th>언어</th>
              <th>복습</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ problem: p, submission: s }) => (
              <tr key={p.id}>
                <td>{p.title}</td>
                <td>
                  <Badge
                    tone={s?.verdict === "AC" ? "success" : s ? "error" : ""}
                  >
                    {s ? labels[s.verdict || s.state] : "미제출"}
                  </Badge>
                </td>
                <td>{s ? `${s.passedCount} / ${s.totalCount}` : "—"}</td>
                <td>{s ? `${s.cpuTimeMs}ms` : "—"}</td>
                <td>{s ? labels[s.language] : "—"}</td>
                <td>
                  <Go className="small" href={`/problems/${p.id}`}>
                    다시 풀기
                  </Go>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="notice">
        실제 시험의 성적이나 정책이 아닙니다. 숨김 테스트의 입력·기대값·반환값은
        공개하지 않습니다.
      </p>
      <div className="row">
        <Go href="/exams">모의 코테 목록</Go>
        <Go className="primary" href="/dashboard">
          학습 현황 보기
        </Go>
      </div>
    </div>
  );
}
