"use client";
import { useState, useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { Users, ArrowRight, Plus } from "lucide-react";
import { api } from "@/services/mock-api";
import { studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type {
  GoalType,
  SkillLevel,
  ActiveTimeSlot,
  JoinType,
  Language,
  MembershipStatus,
  StudySummary,
  StudyRecommendation,
} from "@/services/types";
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
  useAction,
  date,
} from "@/components/ui";
import { Pet } from "@/components/pet";
import { Board } from "./board";
import { ProblemTable, useQuery, Pager } from "./problem-list";
const goalLabel: Record<GoalType, string> = {
  EMPLOYMENT: "취업 준비",
  CONTEST: "대회 준비",
  STUDY: "순수 학습",
};
const skillLabel: Record<SkillLevel, string> = {
  LV0: "LV0",
  LV1: "LV1",
  LV2: "LV2",
  LV3: "LV3",
  MASTER: "MASTER",
};
const timeLabel: Record<ActiveTimeSlot, string> = {
  MORNING: "오전",
  AFTERNOON: "오후",
  EVENING: "저녁",
  NIGHT: "심야",
};
const langLabel: Record<Language, string> = {
  java: "Java",
  python3: "Python",
  cpp: "C++",
};

function StudyCard({
  study,
  membershipStatus,
}: {
  study: StudySummary;
  membershipStatus: MembershipStatus | null;
}) {
  const isApproved = membershipStatus === "APPROVED";
  const isPending = membershipStatus === "PENDING";
  return (
    <article className="card study-card">
      <div className="row between">
        <div className="row">
          {isApproved && <Badge tone="success">참여 중</Badge>}
          {isPending && <Badge tone="pending">신청 중</Badge>}
          <Badge tone={study.joinType === "INSTANT" ? "accent" : undefined}>
            {study.joinType === "INSTANT" ? "즉시 가입" : "승인 필요"}
          </Badge>
        </div>
        <Pet size={36} level={2} />
      </div>
      <h3>{study.name}</h3>
      <p className="muted">
        {goalLabel[study.goal]} · {skillLabel[study.skillLevel]} ·{" "}
        {langLabel[study.preferredLanguage]}
      </p>
      <p className="muted">{timeLabel[study.activeTimeSlot]}</p>
      <p className="row">
        <Users size={16} />
        {study.memberCount} / {study.capacity}명
      </p>
      {study.status === "RECRUITING" && (
        <Go className="actions primary" href={`/studies/${study.studyId}`}>
          {isApproved ? "스터디 입장" : "자세히 보기"}
          <ArrowRight size={16} />
        </Go>
      )}
      {study.status === "ACTIVE" && (
        <Go className="actions" href={`/studies/${study.studyId}`}>
          정원 마감 — 자세히 보기
          <ArrowRight size={16} />
        </Go>
      )}
    </article>
  );
}

function RecCard({
  study,
  membershipStatus,
}: {
  study: StudyRecommendation;
  membershipStatus: MembershipStatus | null;
}) {
  return (
    <article className="card study-card">
      <div className="row between">
        <div className="row">
          {membershipStatus === "APPROVED" && <Badge tone="success">참여 중</Badge>}
          {membershipStatus === "PENDING" && <Badge tone="pending">신청 중</Badge>}
        </div>
        <Pet size={36} level={2} />
      </div>
      <h3>{study.name}</h3>
      <p className="row">
        <Users size={16} />
        {study.memberCount} / {study.capacity}명
      </p>
      {study.reasons[0] && <p className="muted">{study.reasons[0]}</p>}
      <Go className="actions primary" href={`/studies/${study.studyId}`}>
        자세히 보기
        <ArrowRight size={16} />
      </Go>
    </article>
  );
}
export function StudiesPage() {
  const { params, change } = useQuery();
  const [search, setSearch] = useState(params.get("keyword") || "");
  const appliedKeyword = params.get("keyword") || "";
  useEffect(() => setSearch(appliedKeyword), [appliedKeyword]);

  const listKey = [
    params.get("keyword") || "",
    params.get("goal") || "",
    params.get("activeTimeSlot") || "",
    params.get("sort") || "",
    params.get("page") || "0",
  ].join("|");

  const list = useResource(
    () =>
      studyApi.getList({
        keyword: params.get("keyword") || undefined,
        goal: (params.get("goal") as GoalType) || undefined,
        activeTimeSlot: (params.get("activeTimeSlot") as ActiveTimeSlot) || undefined,
        sort: params.get("sort") || undefined,
        page: Number(params.get("page") || 0),
        size: 20,
      }),
    listKey,
  );
  const rec = useResource(() => studyApi.getRecommendations({ size: 5 }));
  const myList = useResource(() => studyApi.getMyStudies({ size: 100 }));

  const membershipMap = useMemo(() => {
    const map = new Map<string, MembershipStatus>();
    myList.data?.content.forEach((m) => map.set(m.studyId, m.membershipStatus));
    return map;
  }, [myList.data]);

  return (
    <div className="container stack">
      <Heading
        title="함께라서 더 멀리, 스터디"
        eyebrow="GROW TOGETHER"
        description="같은 목표를 가진 동료와 꾸준한 코딩 습관을 만들어 보세요."
        action={
          <Go className="primary" href="/studies/new">
            <Plus size={18} />
            스터디 만들기
          </Go>
        }
      />
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          change({ keyword: search, page: "0" });
        }}
      >
        <input
          aria-label="스터디 검색"
          className="input search"
          placeholder="스터디 이름을 검색하세요"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select
          className="input"
          aria-label="학습 목표"
          value={params.get("goal") || ""}
          onChange={(e) => change({ goal: e.target.value, page: "0" })}
        >
          <option value="">모든 목표</option>
          <option value="EMPLOYMENT">취업 준비</option>
          <option value="CONTEST">대회 준비</option>
          <option value="STUDY">순수 학습</option>
        </select>
        <select
          className="input"
          aria-label="활동 시간대"
          value={params.get("activeTimeSlot") || ""}
          onChange={(e) => change({ activeTimeSlot: e.target.value, page: "0" })}
        >
          <option value="">모든 시간대</option>
          <option value="MORNING">오전</option>
          <option value="AFTERNOON">오후</option>
          <option value="EVENING">저녁</option>
          <option value="NIGHT">심야</option>
        </select>
        <select
          className="input"
          aria-label="정렬"
          value={params.get("sort") || ""}
          onChange={(e) => change({ sort: e.target.value, page: "0" })}
        >
          <option value="">최신순</option>
          <option value="lastActivityAt,desc">최근 활동순</option>
          <option value="memberCount,desc">멤버순</option>
        </select>
        <Button>검색</Button>
      </form>

      {!rec.error && rec.data && rec.data.content.length > 0 && (
        <section className="stack">
          <h2>
            {rec.data.mode === "PERSONALIZED" ? "나를 위한 추천 스터디" : "인기 스터디"}
          </h2>
          {rec.data.isProfileRequired && (
            <p className="muted">
              더 정확한 추천을 받으려면 학습 프로필을 입력하세요.
            </p>
          )}
          <div className="grid-3">
            {rec.data.content.map((s) => (
              <RecCard
                key={s.studyId}
                study={s}
                membershipStatus={membershipMap.get(s.studyId) ?? null}
              />
            ))}
          </div>
        </section>
      )}

      <section className="stack">
        <h2>스터디 탐색</h2>
        {list.loading ? (
          <Loading />
        ) : list.error ? (
          <ErrorState message={list.error.message} retry={list.retry} />
        ) : (
          <>
            {list.data!.content.length ? (
              <div className="grid-3">
                {list.data!.content.map((s) => (
                  <StudyCard
                    key={s.studyId}
                    study={s}
                    membershipStatus={membershipMap.get(s.studyId) ?? null}
                  />
                ))}
              </div>
            ) : (
              <Empty
                title="조건에 맞는 스터디가 없어요"
                description="필터를 바꿔보거나 직접 스터디를 만들어 보세요."
              />
            )}
            <Pager
              page={list.data!.page}
              totalPages={list.data!.totalPages}
              onChange={(page) => change({ page: String(page) })}
            />
          </>
        )}
      </section>
    </div>
  );
}
export function StudyPage({ id }: { id: string }) {
  const { tags, problems } = useCatalog();
  const r = useResource(api.snapshot, id),
    [tab, setTab] = useState("intro"),
    action = useAction();
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
  const db = r.data!,
    s = db.studies.find((s) => s.id === id);
  if (!s) return <NotFound what="스터디" />;
  const joined = s.members.includes(db.currentUserId || ""),
    applied = s.applicants.includes(db.currentUserId || "");
  return (
    <div className="container stack">
      <Go className="small" href="/studies">
        ← 스터디 목록
      </Go>
      <Heading
        title={s.title}
        eyebrow="STUDY ROOM"
        description={s.description}
        action={
          <Badge tone="accent">
            {joined ? "참여 중" : applied ? "신청 중" : "모집 중"}
          </Badge>
        }
      />
      <div className="detail-grid">
        <section className="card stack">
          <Tabs
            value={tab}
            onChange={setTab}
            items={[
              { id: "intro", label: "소개" },
              { id: "members", label: "구성원" },
              { id: "problems", label: "주간 문제집" },
              { id: "board", label: "게시판" },
            ]}
          />
          {tab === "intro" && (
            <div className="stack">
              <Pet size={100} />
              <h2>함께 만드는 꾸준함</h2>
              <p>{s.description}</p>
              <div className="inset stack-sm">
                <h3>우리의 목표</h3>
                <p>{s.goal}</p>
                <p>참가 조건: {s.condition || "누구나 환영해요."}</p>
                <p>진행 시간: {s.time}</p>
              </div>
              <p className="muted">
                가입 신청은 목업으로 기록됩니다. 실제 승인·알림은 제공하지
                않습니다.
              </p>
            </div>
          )}
          {tab === "members" && (
            <div className="stack">
              {s.members.map((uid) => (
                <div className="row inset" key={uid}>
                  <Pet size={40} />
                  <b>{db.users.find((u) => u.id === uid)?.name || "멤버"}</b>
                  {uid === s.ownerId && <Badge tone="accent">스터디장</Badge>}
                </div>
              ))}
            </div>
          )}
          {tab === "problems" && (
            <ProblemTable
              items={problems
                .filter((p) => s.problemIds.includes(p.id))
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
          )}{" "}
          {tab === "board" && <Board scope={`study:${id}`} />}
        </section>
        <div className="stack">
          <Section title="스터디 안내">
            <div className="stack-sm">
              <p>
                모집 인원{" "}
                <b>
                  {s.members.length} / {s.capacity}명
                </b>
              </p>
              <p>
                난이도 <b>{s.level}</b>
              </p>
              <p>모집 마감 {date(s.deadline)}</p>
              <p>공개 범위 {s.visibility === "public" ? "공개" : "비공개"}</p>
              <div className="row wrap">
                {s.tagIds.map((t) => (
                  <Badge key={t}>{tags.find((x) => x.id === t)?.name}</Badge>
                ))}
              </div>
              <Button
                className="primary full"
                disabled={joined}
                onClick={() =>
                  action(
                    () => api.applyStudy(id),
                    applied
                      ? "가입 신청을 취소했습니다."
                      : "가입 신청을 기록했습니다.",
                  )
                }
              >
                {joined
                  ? "참여 중인 스터디"
                  : applied
                    ? "가입 신청 취소"
                    : "가입 신청"}
              </Button>
            </div>
          </Section>
        </div>
      </div>
    </div>
  );
}
export function StudyCreate() {
  const action = useAction(),
    router = useRouter(),
    [busy, setBusy] = useState(false);
  return (
    <div className="container">
      <Heading
        title="스터디 만들기"
        eyebrow="NEW STUDY"
        description="작은 목표부터 함께 시작해 보세요."
      />
      <form
        className="card form-card stack"
        onSubmit={async (e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          setBusy(true);
          await action(
            async () => {
              let studyId: string;
              try {
                const res = await studyApi.create({
                  name: String(f.get("name")).trim(),
                  description: String(f.get("description")).trim(),
                  goal: f.get("goal") as GoalType,
                  skillLevel: f.get("skillLevel") as SkillLevel,
                  preferredLanguage: f.get("preferredLanguage") as Language,
                  activeTimeSlot: f.get("activeTimeSlot") as ActiveTimeSlot,
                  capacity: Number(f.get("capacity")),
                  joinType: f.get("joinType") as JoinType,
                  isAutoReopen: f.get("isAutoReopen") === "on",
                });
                studyId = res.studyId;
              } catch (err) {
                const code = (err as { code?: string }).code;
                throw new Error(
                  code === "STUDY_JOIN_LIMIT_EXCEEDED"
                    ? "최대 5개 스터디에 참여할 수 있습니다."
                    : code === "MEMBER_SUSPENDED"
                      ? "계정이 정지되어 스터디를 만들 수 없습니다."
                      : ((err as { message?: string }).message ?? "오류가 발생했습니다."),
                );
              }
              router.push(`/studies/${studyId}`);
            },
            "스터디를 만들었습니다.",
            false,
          );
          setBusy(false);
        }}
      >
        <label className="field">
          스터디 이름
          <input
            className="input"
            name="name"
            required
            minLength={1}
            maxLength={200}
            placeholder="예: 매일 한 문제, 코테 루틴"
          />
        </label>
        <label className="field">
          스터디 소개
          <textarea
            className="input"
            name="description"
            maxLength={500}
            placeholder="스터디를 소개해 주세요. (선택)"
          />
        </label>
        <div className="form-grid">
          <label className="field">
            학습 목표
            <select className="input" name="goal" required>
              <option value="EMPLOYMENT">취업 준비</option>
              <option value="CONTEST">대회 준비</option>
              <option value="STUDY">순수 학습</option>
            </select>
          </label>
          <label className="field">
            실력 수준
            <select className="input" name="skillLevel" required>
              <option value="LV0">LV0 · 입문</option>
              <option value="LV1">LV1 · 초급</option>
              <option value="LV2">LV2 · 중급</option>
              <option value="LV3">LV3 · 고급</option>
              <option value="MASTER">MASTER</option>
            </select>
          </label>
          <label className="field">
            선호 언어
            <select className="input" name="preferredLanguage" required>
              <option value="java">Java</option>
              <option value="python3">Python</option>
              <option value="cpp">C++</option>
            </select>
          </label>
          <label className="field">
            활동 시간대
            <select className="input" name="activeTimeSlot" required>
              <option value="MORNING">오전</option>
              <option value="AFTERNOON">오후</option>
              <option value="EVENING">저녁</option>
              <option value="NIGHT">심야</option>
            </select>
          </label>
          <label className="field">
            최대 인원
            <input
              className="input"
              name="capacity"
              type="number"
              min={2}
              max={10}
              defaultValue={10}
              required
            />
          </label>
        </div>
        <fieldset className="stack-sm">
          <legend>가입 방식</legend>
          <div className="row">
            <label className="checkbox-row">
              <input type="radio" name="joinType" value="INSTANT" defaultChecked />
              즉시 가입
            </label>
            <label className="checkbox-row">
              <input type="radio" name="joinType" value="APPROVAL" />
              승인 후 가입
            </label>
          </div>
        </fieldset>
        <label className="checkbox-row">
          <input type="checkbox" name="isAutoReopen" />
          모집 완료 후 자동 재모집
        </label>
        <div className="row">
          <Go href="/studies">취소</Go>
          <Button className="primary grow" disabled={busy}>
            스터디 생성
          </Button>
        </div>
      </form>
    </div>
  );
}
