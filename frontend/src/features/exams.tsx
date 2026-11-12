"use client";
import { useState, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Clock, Users, Plus, Flag } from "lucide-react";
import { api, labels } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import { contestApi, studyApi } from "@/services/api";
import type {
  ExamStatusServer,
  ExamSummary,
} from "@/services/types";
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
import { useQuery, pageSlice, Pager } from "./problem-list";

const serverLabels: Record<ExamStatusServer, string> = {
  SCHEDULED: "예정",
  IN_PROGRESS: "진행 중",
  CLOSED: "종료됨",
  FINALIZED: "최종 확정",
  CANCELED: "취소",
};

const serverTone = (s: ExamStatusServer) =>
  s === "IN_PROGRESS" ? "success" : s === "SCHEDULED" ? "accent" : "";

export function ExamsPage({
  contest = false,
  studyId,
}: {
  contest?: boolean;
  studyId?: string;
}) {
  const { params, change } = useQuery();
  const appliedKeyword = params.get("keyword") || "";
  const [search, setSearch] = useState(appliedKeyword);
  useEffect(() => setSearch(appliedKeyword), [appliedKeyword]);
  const status = params.get("status") || "all";

  const realR = useResource(
    () =>
      studyId
        ? contestApi.getExamList(studyId)
        : Promise.resolve({
            items: [] as ExamSummary[],
            page: 0,
            size: 0,
            totalElements: 0,
            totalPages: 0,
          }),
    studyId ?? "none",
  );

  const mockR = useResource(
    () => api.exams(contest ? "contest" : "exam"),
    String(contest),
  );

  if (studyId) {
    const items = (realR.data?.items ?? []).filter(
      (e) =>
        (status === "all" || e.status === status) &&
        e.title.includes(appliedKeyword),
    );
    return (
      <div className="container stack">
        <Heading
          title="모의 코딩테스트"
          eyebrow="MOCK EXAM"
          description="시간 안에 생각을 코드로. 꾸준한 연습을 실전 감각으로 바꿔 보세요."
          action={
            <Go className="primary" href={`/exams/new?studyId=${studyId}`}>
              <Plus size={18} />
              시험 만들기
            </Go>
          }
        />
        <Tabs
          value={status}
          onChange={(s) => change({ status: s })}
          items={(
            [
              "all",
              ...Object.keys(serverLabels),
            ] as Array<"all" | ExamStatusServer>
          ).map((s) => ({
            id: s,
            label: s === "all" ? "전체" : serverLabels[s as ExamStatusServer],
          }))}
        />
        {realR.loading ? (
          <Loading />
        ) : realR.error ? (
          <ErrorState message={realR.error.message} retry={realR.retry} />
        ) : items.length ? (
          <div className="grid-3">
            {items.map((e) => (
              <article key={e.examId} className="card exam-card">
                <div className="row between">
                  <Badge tone={serverTone(e.status)}>
                    {serverLabels[e.status]}
                  </Badge>
                  <Flag size={24} />
                </div>
                <h3>{e.title}</h3>
                <p>{date(new Date(e.startsAt).getTime())}</p>
                <div className="row wrap">
                  <span>{e.problemCount}문제</span>
                  <span className="row">
                    <Users size={16} />
                    {e.participantCount}명
                  </span>
                </div>
                <Go
                  className={`actions ${
                    e.status === "FINALIZED" || e.status === "CLOSED"
                      ? ""
                      : "primary"
                  }`}
                  href={`/exams/${e.examId}`}
                >
                  {e.status === "FINALIZED" || e.status === "CLOSED"
                    ? "결과 살펴보기"
                    : "자세히 보기"}
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
      </div>
    );
  }

  // mock path (unchanged)
  const matching = (mockR.data || []).filter(
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
      {mockR.loading ? (
        <Loading />
      ) : mockR.error ? (
        <ErrorState message={mockR.error.message} retry={mockR.retry} />
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
  const r = useResource(() => contestApi.getExamDetail(id), id);
  const router = useRouter();
  const action = useAction();

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
  const e = r.data;
  if (!e) return <NotFound what="시험" />;

  const joined = !!e.myParticipation;
  const ended =
    e.status === "CLOSED" ||
    e.status === "FINALIZED" ||
    e.status === "CANCELED";
  const myFinished = e.myParticipation?.status === "FINISHED";

  return (
    <div className="container stack">
      <Go className="small" href={contest ? "/contests" : "/exams"}>
        ← 목록으로
      </Go>
      <Heading
        title={e.title}
        eyebrow="READY TO CHALLENGE"
        description=""
        action={
          <Badge tone={serverTone(e.status)}>{serverLabels[e.status]}</Badge>
        }
      />
      <div className="detail-grid">
        <Section title="시험 안내">
          <div className="stack">
            <div className="row">
              <Pet level={3} />
              <div>
                <h3>집중하는 시간, 성장하는 실력</h3>
                <p className="muted">
                  응시를 시작한 시각부터 종료 시각까지 제한이 적용됩니다.
                </p>
              </div>
            </div>
            <div className="inset stack-sm">
              <p>시작 일시: {date(new Date(e.startsAt).getTime())}</p>
              <p>종료 일시: {date(new Date(e.endsAt).getTime())}</p>
              <p>총점: {e.totalScore}점</p>
              <p>문제 수: {e.problemCount}개</p>
            </div>
            <p>Java, Python, C++을 사용할 수 있습니다.</p>
          </div>
        </Section>
        <Section title="대기실">
          <div className="stack">
            {ended || myFinished ? (
              <Go className="primary" href={`/exams/${id}/result`}>
                결과 확인
              </Go>
            ) : (
              <>
                {joined ? (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          contestApi
                            .cancelParticipant(id)
                            .then(() => { router.refresh(); r.retry(); }),
                        "참가를 취소했습니다.",
                      )
                    }
                  >
                    참가 취소
                  </Button>
                ) : (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          contestApi
                            .registerParticipant(id)
                            .then(() => { router.refresh(); r.retry(); }),
                        "참가 신청했습니다.",
                      )
                    }
                  >
                    참가 신청
                  </Button>
                )}
                <Button
                  className="primary"
                  onClick={() =>
                    action(async () => {
                      await contestApi.startExam(id);
                      router.push(`/exams/${id}/room`);
                    })
                  }
                >
                  {e.myParticipation?.status === "STARTED"
                    ? "이어서 응시"
                    : "응시 시작"}
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

type ProblemItem = { problemId: string; title: string; difficulty: number };

export function ExamCreate() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const studyId = searchParams.get("studyId") ?? "";
  const action = useAction();

  const [problems, setProblems] = useState<ProblemItem[]>([]);
  const [loadingProblems, setLoadingProblems] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [scores, setScores] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!studyId) return;
    setLoadingProblems(true);
    studyApi
      .getAssignments(studyId)
      .then((page) =>
        Promise.all(
          page.content.map((a) =>
            studyApi.getAssignmentDetail(studyId, a.assignmentId),
          ),
        ),
      )
      .then((details) => {
        const seen = new Set<string>();
        const flat: ProblemItem[] = [];
        details.forEach((d) =>
          d.problems.forEach((p) => {
            if (!seen.has(p.problemId)) {
              seen.add(p.problemId);
              flat.push({
                problemId: p.problemId,
                title: p.title,
                difficulty: p.difficulty,
              });
            }
          }),
        );
        setProblems(flat);
        const defaultScores: Record<string, string> = {};
        flat.forEach((p) => (defaultScores[p.problemId] = "100"));
        setScores(defaultScores);
      })
      .catch(() => {})
      .finally(() => setLoadingProblems(false));
  }, [studyId]);

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
            const result = await contestApi.createExam(studyId, {
              title: String(f.get("title")).trim(),
              mode: "FIXED",
              startsAt: new Date(String(f.get("startsAt"))).toISOString(),
              endsAt: new Date(String(f.get("endsAt"))).toISOString(),
              problems: selected.map((pid, idx) => ({
                problemId: pid,
                score: scores[pid] ?? "100",
                displayOrder: idx + 1,
              })),
            });
            router.push(`/exams/${result.examId}`);
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
            종료 일시
            <input
              className="input"
              name="endsAt"
              type="datetime-local"
              required
            />
          </label>
        </div>
        <fieldset className="stack-sm">
          <legend>
            문제 선택 · {selected.length}개
            {loadingProblems && " (불러오는 중…)"}
          </legend>
          {problems.map((p) => (
            <div className="row" key={p.problemId}>
              <label className="checkbox-row grow">
                <input
                  type="checkbox"
                  checked={selected.includes(p.problemId)}
                  onChange={(ev) =>
                    setSelected(
                      ev.target.checked
                        ? [...selected, p.problemId]
                        : selected.filter((id) => id !== p.problemId),
                    )
                  }
                />
                <span className="grow">
                  {p.problemId}. {p.title}
                </span>
                <Badge>Lv.{p.difficulty}</Badge>
              </label>
              {selected.includes(p.problemId) && (
                <input
                  className="input"
                  style={{ width: "5rem" }}
                  type="number"
                  aria-label={`${p.title} 배점`}
                  min={1}
                  value={scores[p.problemId] ?? "100"}
                  onChange={(ev) =>
                    setScores((s) => ({ ...s, [p.problemId]: ev.target.value }))
                  }
                />
              )}
            </div>
          ))}
          {!selected.length && (
            <p className="error-text">문제를 1개 이상 선택해 주세요.</p>
          )}
        </fieldset>
        <div className="row">
          <Go href={studyId ? `/studies/${studyId}` : "/exams"}>취소</Go>
          <Button
            className="primary grow"
            disabled={busy || !selected.length || !studyId}
          >
            시험 생성
          </Button>
        </div>
      </form>
    </div>
  );
}
