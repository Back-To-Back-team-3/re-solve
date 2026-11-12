"use client";
import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Clock, Users, Plus, Flag } from "lucide-react";
import { api, labels } from "@/services/mock-api";
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
  NotFound,
  Section,
  Tabs,
  date,
  useAction,
} from "@/components/ui";
import { Pet } from "@/components/pet";
import { ProblemTable, useQuery, pageSlice, Pager } from "./problem-list";
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
        <ErrorState message={r.error.message} retry={r.retry} />
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
        <ErrorState message={r.error.message} retry={r.retry} />
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
              items={problems
                .filter((p) => e.problemIds.includes(p.id))
                .map((p) => ({
                  problemId: p.id,
                  problemRevisionId: p.problemRevisionId,
                  revisionNumber: 0,
                  title: p.title,
                  difficulty: p.difficulty,
                  tags: p.tags,
                  publishedAt: "",
                  solvedStatus: "UNSOLVED" as const,
                  supportedLanguages: [],
                }))}
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
