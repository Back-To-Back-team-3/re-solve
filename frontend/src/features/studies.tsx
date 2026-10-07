"use client";
import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Users, Clock, ArrowRight, Plus } from "lucide-react";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import type { Study } from "@/services/types";
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
import { ProblemTable, useQuery, pageSlice, Pager } from "./problem-list";
function StudyCard({ study, userId }: { study: Study; userId: string | null }) {
  const { tags } = useCatalog();
  const member = study.members.includes(userId || ""),
    applicant = study.applicants.includes(userId || "");
  return (
    <article className="card study-card">
      <div className="row between">
        <Badge tone={member ? "success" : applicant ? "pending" : "accent"}>
          {member ? "참여 중" : applicant ? "가입 신청 중" : "모집 중"}
        </Badge>
        <Pet size={36} level={2} />
      </div>
      <h3>{study.title}</h3>
      <p className="muted">{study.description}</p>
      <div className="row wrap">
        {study.tagIds.map((t) => (
          <Badge key={t}>{tags.find((x) => x.id === t)?.name}</Badge>
        ))}
      </div>
      <p className="row">
        <Users size={16} />
        {study.members.length} / {study.capacity}명{" "}
        <span className="muted">{study.level}</span>
      </p>
      <p className="row">
        <Clock size={16} />
        {study.time}
      </p>
      <div className="notice">{study.reason}</div>
      <Go className="actions primary" href={`/studies/${study.id}`}>
        {member ? "스터디 입장" : "자세히 보기"}
        <ArrowRight size={16} />
      </Go>
    </article>
  );
}
export function StudiesPage() {
  const { tags } = useCatalog();
  const { params, change } = useQuery(),
    r = useResource(api.snapshot);
  const [search, setSearch] = useState(params.get("keyword") || "");
  const appliedKeyword = params.get("keyword") || "";
  useEffect(() => setSearch(appliedKeyword), [appliedKeyword]);
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
  const db = r.data!,
    matches = db.studies.filter(
      (s) =>
        (!params.get("keyword") || s.title.includes(params.get("keyword")!)) &&
        (!params.get("tag") || s.tagIds.includes(params.get("tag")!)),
    ),
    mine = matches.filter((s) => s.members.includes(db.currentUserId || "")),
    recommended = matches.filter(
      (s) => !s.members.includes(db.currentUserId || ""),
    );
  const order = (a: Study, b: Study) =>
    params.get("sort") === "members"
      ? b.members.length - a.members.length
      : params.get("sort") === "title"
        ? a.title.localeCompare(b.title)
        : b.deadline - a.deadline;
  const recPage = pageSlice(recommended.sort(order), params.get("page"), 3),
    myPage = pageSlice(mine.sort(order), params.get("minePage"), 3);
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
          change({ keyword: search, page: "0", minePage: "0" });
        }}
      >
        <input
          aria-label="스터디 검색"
          className="input search"
          placeholder="찾고 싶은 스터디를 검색하세요"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select
          className="input"
          aria-label="스터디 알고리즘"
          value={params.get("tag") || ""}
          onChange={(e) =>
            change({ tag: e.target.value, page: "0", minePage: "0" })
          }
        >
          <option value="">모든 알고리즘</option>
          {tags.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        <select
          className="input"
          aria-label="스터디 정렬"
          value={params.get("sort") || ""}
          onChange={(e) =>
            change({ sort: e.target.value, page: "0", minePage: "0" })
          }
        >
          <option value="">최신순</option>
          <option value="members">참여 인원순</option>
          <option value="title">이름순</option>
        </select>
        <Button>검색</Button>
      </form>
      <section className="stack">
        <h2>나에게 맞는 추천 스터디</h2>
        {recommended.length ? (
          <div className="grid-3">
            {recPage.items.map((s) => (
              <StudyCard key={s.id} study={s} userId={db.currentUserId} />
            ))}
          </div>
        ) : (
          <Empty
            title="조건에 맞는 스터디가 없어요"
            description="직접 스터디를 만들거나 다른 검색 조건을 선택해 보세요."
          />
        )}
        <Pager
          {...recPage}
          onChange={(page) => change({ page: String(page) })}
        />
      </section>
      <section className="stack">
        <h2>
          참여 중인 스터디 <Badge>{mine.length}</Badge>
        </h2>
        {mine.length ? (
          <div className="grid-3">
            {myPage.items.map((s) => (
              <StudyCard key={s.id} study={s} userId={db.currentUserId} />
            ))}
          </div>
        ) : (
          <Empty title="함께할 동료를 찾아보세요" />
        )}
        <Pager
          {...myPage}
          onChange={(page) => change({ minePage: String(page) })}
        />
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
        <ErrorState message={r.error} retry={r.retry} />
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
              items={problems.filter((p) => s.problemIds.includes(p.id))}
              db={db}
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
  const { tags, problems } = useCatalog();
  const action = useAction(),
    router = useRouter(),
    [selected, setSelected] = useState<string[]>(["hash"]),
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
          if (!selected.length) return;
          setBusy(true);
          await action(async () => {
            const s = await api.createStudy({
              title: String(f.get("title")).trim(),
              description: String(f.get("description")).trim(),
              goal: String(f.get("goal")),
              level: String(f.get("level")),
              time: String(f.get("time")),
              capacity: Number(f.get("capacity")),
              visibility: f.get("visibility") as "public" | "private",
              condition: String(f.get("condition")),
              tagIds: selected,
              problemIds: problems
                .filter((p) => selected.includes(p.tagId))
                .map((p) => p.id),
            });
            router.push(`/studies/${s.id}`);
          }, "스터디를 만들었습니다.");
          setBusy(false);
        }}
      >
        <label className="field">
          스터디 이름
          <input
            className="input"
            name="title"
            required
            minLength={2}
            maxLength={60}
            placeholder="예: 매일 한 문제, 코테 루틴"
          />
        </label>
        <label className="field">
          스터디 소개
          <textarea
            className="input"
            name="description"
            required
            minLength={10}
            maxLength={1000}
            placeholder="함께할 활동을 10자 이상 소개해 주세요."
          />
        </label>
        <label className="field">
          학습 목표
          <input
            className="input"
            name="goal"
            required
            placeholder="예: 매주 3문제 풀고 풀이 공유하기"
          />
        </label>
        <div className="form-grid">
          <label className="field">
            난이도
            <select className="input" name="level">
              <option>입문 · Lv. 1–2</option>
              <option>중급 · Lv. 3</option>
              <option>심화 · Lv. 4–5</option>
            </select>
          </label>
          <label className="field">
            최대 인원
            <input
              className="input"
              name="capacity"
              type="number"
              min={2}
              max={30}
              defaultValue={6}
              required
            />
          </label>
          <label className="field">
            진행 시간
            <input
              className="input"
              name="time"
              placeholder="매주 수요일 20:00"
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
        </div>
        <fieldset className="stack-sm">
          <legend>알고리즘 태그 (1개 이상)</legend>
          <div className="row wrap">
            {tags.map((t) => (
              <label key={t.id} className="checkbox-row">
                <input
                  type="checkbox"
                  checked={selected.includes(t.id)}
                  onChange={(e) =>
                    setSelected(
                      e.target.checked
                        ? [...selected, t.id]
                        : selected.filter((x) => x !== t.id),
                    )
                  }
                />
                {t.name}
              </label>
            ))}
          </div>
          {!selected.length && (
            <p className="error-text">태그를 1개 이상 선택해 주세요.</p>
          )}
        </fieldset>
        <label className="field">
          참가 조건
          <input
            className="input"
            name="condition"
            placeholder="누구나 환영합니다"
          />
        </label>
        <div className="row">
          <Go href="/studies">취소</Go>
          <Button className="primary grow" disabled={busy || !selected.length}>
            스터디 생성
          </Button>
        </div>
      </form>
    </div>
  );
}
