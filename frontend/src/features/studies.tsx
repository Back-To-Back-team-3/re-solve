"use client";
import { useState, useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { Users, ArrowRight, Plus } from "lucide-react";
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
import {
  Badge,
  Button,
  Empty,
  ErrorState,
  Go,
  Heading,
  Loading,
  Section,
  Tabs,
  useAction,
} from "@/components/ui";
import { Pet } from "@/components/pet";
import { useQuery, Pager } from "./problem-list";
import { MembersTab } from "./study-members";
import { AssignmentsTab } from "./study-assignments";
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
  const [tab, setTab] = useState("intro");
  const [version, setVersion] = useState(0);
  const action = useAction();
  const r = useResource(() => studyApi.getDetail(id), `${id}:${version}`);
  const refresh = () => setVersion((v) => v + 1);

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

  const study = r.data!;
  const myM = study.myMembership;
  const isApproved = myM?.status === "APPROVED";
  const isPending = myM?.status === "PENDING";
  const isLeader = myM?.role === "LEADER";

  const statusLabel = { RECRUITING: "모집 중", ACTIVE: "진행 중", CLOSED: "종료됨" }[study.status];
  const statusTone =
    study.status === "RECRUITING" ? "success" : study.status === "ACTIVE" ? "accent" : undefined;

  const memberGuard = !isApproved ? (
    <p className="muted">
      {isPending ? "승인 후 이용할 수 있습니다." : "구성원만 이용할 수 있습니다."}
    </p>
  ) : null;

  async function handleJoin() {
    await action(
      async () => {
        try {
          await studyApi.join(id);
          refresh();
        } catch (err) {
          const code = (err as { code?: string }).code;
          throw new Error(
            code === "STUDY_FULL"
              ? "정원이 가득 찼습니다."
              : code === "STUDY_JOIN_LIMIT_EXCEEDED"
                ? "최대 5개 스터디에 참여할 수 있습니다."
                : code === "STUDY_ALREADY_APPLIED"
                  ? "이미 신청하셨습니다."
                  : code === "STUDY_NOT_RECRUITING"
                    ? "현재 모집 중이 아닙니다."
                    : ((err as { message?: string }).message ?? "오류가 발생했습니다."),
          );
        }
      },
      study.joinType === "INSTANT"
        ? "가입이 완료됐습니다."
        : "신청이 완료됐습니다. 7일 내 승인되지 않으면 자동으로 취소됩니다.",
      false,
    );
  }

  async function handleLeave() {
    if (!myM) return;
    if (isLeader) {
      window.alert("스터디장은 다른 멤버에게 위임한 뒤 탈퇴할 수 있습니다.");
      return;
    }
    if (!window.confirm(isPending ? "신청을 취소하시겠습니까?" : "스터디에서 탈퇴하시겠습니까?"))
      return;
    await action(
      async () => {
        await studyApi.leave(id, myM.membershipId);
        refresh();
      },
      isPending ? "신청을 취소했습니다." : "탈퇴했습니다.",
      false,
    );
  }

  return (
    <div className="container stack">
      <Go className="small" href="/studies">
        ← 스터디 목록
      </Go>
      <Heading
        title={study.name}
        eyebrow="STUDY ROOM"
        description={study.description}
        action={<Badge tone={statusTone}>{statusLabel}</Badge>}
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
            ]}
          />
          {tab === "intro" && (
            <div className="stack">
              <Pet size={100} />
              <div className="inset stack-sm">
                <p>
                  <b>학습 목표</b> {goalLabel[study.goal]}
                </p>
                <p>
                  <b>실력 수준</b> {skillLabel[study.skillLevel]}
                </p>
                <p>
                  <b>선호 언어</b> {langLabel[study.preferredLanguage]}
                </p>
                <p>
                  <b>활동 시간대</b> {timeLabel[study.activeTimeSlot]}
                </p>
                <p>
                  <b>가입 방식</b>{" "}
                  {study.joinType === "INSTANT" ? "즉시 가입" : "승인 후 가입"}
                </p>
                {study.isAutoReopen && (
                  <p className="muted">정원이 차면 자동으로 모집을 재개합니다.</p>
                )}
                <p>
                  <b>스터디장</b> {study.leader.nickname}
                </p>
              </div>
              <p>{study.description}</p>
            </div>
          )}
          {tab === "members" && (
            <div className="stack">
              {memberGuard ?? (
                <MembersTab
                  studyId={id}
                  myMembership={myM}
                  version={version}
                  onRefresh={refresh}
                />
              )}
            </div>
          )}
          {tab === "problems" && (
            <div className="stack">
              {memberGuard ?? (
                <AssignmentsTab studyId={id} version={version} />
              )}
            </div>
          )}
        </section>
        <div className="stack">
          <Section title="스터디 안내">
            <div className="stack-sm">
              <p>
                모집 인원{" "}
                <b>
                  {study.memberCount} / {study.capacity}명
                </b>
              </p>
              <p>
                상태 <b>{statusLabel}</b>
              </p>
              <p>
                스터디장 <b>{study.leader.nickname}</b>
              </p>
              {!myM && study.status === "RECRUITING" && (
                <Button className="primary full" onClick={handleJoin}>
                  {study.joinType === "INSTANT" ? "가입" : "신청하기"}
                </Button>
              )}
              {myM && (
                <Button className="full" onClick={handleLeave}>
                  {isPending ? "신청 취소" : isLeader ? "탈퇴 (위임 필요)" : "탈퇴"}
                </Button>
              )}
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
