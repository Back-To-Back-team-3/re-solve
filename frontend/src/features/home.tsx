"use client";
import { useState } from "react";
import Link from "next/link";
import { ArrowRight, Flame, BookOpen, Trophy } from "lucide-react";
import { Pet } from "@/components/pet";
import {
  Badge,
  Button,
  Empty,
  Go,
  Heading,
  Section,
  Progress,
  Loading,
  ErrorState,
  Modal,
  useToast,
} from "@/components/ui";
import { judgeApi, studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { useCatalog } from "@/services/catalog";
import { ProblemTable } from "./problem-list";
import type { SubmissionHistoryItem } from "@/services/types";

export function relativeDate(iso: string): string {
  const startOfLocalDay = (d: Date) =>
    new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round(
    (startOfLocalDay(new Date()) - startOfLocalDay(new Date(iso))) / 86400000,
  );
  if (days === 0) return "오늘";
  if (days === 1) return "어제";
  return `${days}일 전`;
}

const VERDICT_LABEL: Record<string, string> = {
  AC: "맞았습니다",
  WA: "틀렸습니다",
  TLE: "시간 초과",
  CE: "컴파일 오류",
  MLE: "메모리 초과",
  RE: "런타임 오류",
};

function RecentSubmissions({ items }: { items: SubmissionHistoryItem[] }) {
  const { problems } = useCatalog();
  if (!items.length) return <Empty title="아직 제출 내역이 없습니다." />;
  return (
    <div className="stack-sm">
      {items.map((s) => {
        const title = problems.find((p) => p.id === s.problemId)?.title ?? s.problemId;
        return (
          <Link key={s.submissionId} href={`/problems/${s.problemId}`} className="activity-row">
            <BookOpen size={18} />
            <div className="grow">
              <b>{title}</b>
              <p className="muted">
                {VERDICT_LABEL[s.verdict ?? ""] ?? s.status} · {relativeDate(s.submittedAt)}
              </p>
            </div>
          </Link>
        );
      })}
    </div>
  );
}

export function HomePage() {
  const { tags, problems } = useCatalog();
  const r = useResource(() => judgeApi.getMySubmissions(0, 5), "home-submissions");
  const toast = useToast();
  const [hero, setHero] = useState(tags[0]?.id ?? ""),
    [screen, setScreen] = useState("pet"),
    [modal, setModal] = useState(""),
    [levels, setLevels] = useState<Record<string, number>>({});
  const current = tags.find((t) => t.id === hero) ?? tags[0];
  const petLevel = current ? levels[current.id] ?? current.level : 0;
  const stages = ["알", "아기", "어린이", "어른", "마스터"];
  const speech =
    petLevel === 0
      ? "톡톡… 알을 깨워 줄래요? 진단을 보거나 아기로 부화시켜 주세요."
      : petLevel === 4
        ? "왕관 어때요? 그래도 가끔 난이도 3 문제 하나씩은 주세요!"
        : "조금씩, 꾸준히 잘하고 있어!";

  return (
    <div className="container stack">
      <div className="hero-grid">
        <section className="card hero">
          <div className="grow stack">
            <h1>
              문제를 먹고 자라는
              <br />
              나만의 알고리즘 펫
            </h1>
            <p>
              태그마다 펫이 한 마리씩. 문제를 풀면 알에서 아기, 어린이, 어른으로
              자라고 마스터가 되면 왕관을 써요.
            </p>
            {current && (
              <div className="speech">
                <b>
                  {petLevel === 0 ? "??? (알)" : `${current.pet} · ${stages[petLevel]}`}
                </b>
                <p>&ldquo;{speech}&rdquo;</p>
              </div>
            )}
            <div className="row wrap">
              <Go className="secondary" href={`/problems?tag=${hero}`}>
                문제 풀러 가기 <ArrowRight size={16} />
              </Go>
              <Button onClick={() => setModal("diagnosis")}>내 실력 진단</Button>
            </div>
          </div>
          {current && (
            <div className="device">
              <span className="pixel">RE:GOTCHI</span>
              <div className="lcd">
                <div className="row between full">
                  <span className="pixel">{current.pet}</span>
                  <span>Lv.{petLevel}</span>
                </div>
                {screen === "pet" ? (
                  <Pet level={petLevel} size={100} label={current.pet} />
                ) : (
                  <div className="stack-sm">
                    <b>{screen === "status" ? "알고리즘 성장 상태" : "오늘의 응원"}</b>
                    <span>
                      {screen === "status" ? `${current.name} 태그` : "조금씩, 꾸준히 잘하고 있어!"}
                    </span>
                  </div>
                )}
                <span className="hearts">♥ ♥ ♥ ♡</span>
              </div>
              <div className="device-controls">
                {[
                  ["A", "펫", "pet"],
                  ["B", "상태", "status"],
                  ["C", "응원", "cheer"],
                ].map(([a, b, c], i) => (
                  <div key={a}>
                    <button
                      className={`round ${i === 1 ? "pink" : i === 2 ? "white" : ""}`}
                      aria-label={`${b} 화면`}
                      onClick={() => setScreen(c)}
                    >
                      {a}
                    </button>
                    <small>{b}</small>
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>
      </div>
      <section>
        <div className="section-heading">
          <div>
            <p className="eyebrow">MY LITTLE ALGORITHM</p>
            <h2>나의 알고리즘 펫</h2>
          </div>
          <Badge>{tags.length}개의 성장 이야기</Badge>
        </div>
        <div className="pet-grid">
          {tags.map((t) => {
            const level = levels[t.id] ?? t.level;
            return (
              <article
                className={`card pet-card ${level === 0 ? "egg" : level === 4 ? "master" : ""}`}
                key={t.id}
              >
                <div className="row between">
                  <span className="pixel">
                    {level === 0 ? "EGG" : level === 4 ? "MASTER" : `LEVEL ${level}`}
                  </span>
                  <Badge>{t.name}</Badge>
                </div>
                <button
                  className="lcd"
                  aria-label={`${t.pet} 대표 펫 선택`}
                  onClick={() => {
                    setHero(t.id);
                    toast(`${t.pet}을 대표 펫으로 선택했어요.`);
                  }}
                >
                  <Pet level={level} size={76} label={t.pet} />
                </button>
                <h3>{t.pet}</h3>
                <p className="muted">
                  {level === 0 ? "새로운 가능성이 잠들어 있어요." : `${t.name} 태그 학습 중`}
                </p>
                <Progress value={level === 0 ? 0 : level * 23} />
                <div className="row actions">
                  {level === 0 ? (
                    <Button className="primary full" onClick={() => setModal(t.id)}>
                      알 부화하기
                    </Button>
                  ) : (
                    <>
                      <Go className="small grow" href={`/problems?tag=${t.id}`}>
                        문제 풀기
                      </Go>
                      <Button
                        className="small"
                        onClick={() => {
                          setHero(t.id);
                          setModal("diagnosis");
                        }}
                      >
                        진단
                      </Button>
                    </>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      </section>
      <div className="home-bottom">
        <Section title="오늘의 도전" href="/problems">
          <ProblemTable
            items={problems.slice(0, 4).map((p) => ({
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
        <Section title="최근 제출" href="/mypage">
          {r.loading ? (
            <Loading />
          ) : r.error ? (
            <ErrorState message={r.error.message} retry={r.retry} />
          ) : (
            <RecentSubmissions items={r.data?.content ?? []} />
          )}
        </Section>
      </div>
      <Modal
        open={!!modal}
        onOpenChange={() => setModal("")}
        title={modal === "diagnosis" ? "알고리즘 성장 진단" : "새 친구를 만나볼까요?"}
        description={
          modal === "diagnosis"
            ? "진단은 고정된 예시 결과입니다. 실제 풀이 분석은 진행하지 않습니다."
            : "부화는 이 화면의 상태만 바꿉니다. 문제는 언제든 자유롭게 풀 수 있어요."
        }
      >
        <div className="stack">
          <div className="row">
            <Pet level={modal === "diagnosis" ? petLevel : 0} />
            <div>
              <h3>
                {modal === "diagnosis"
                  ? `${current?.pet ?? ""}의 다음 목표`
                  : tags.find((t) => t.id === modal)?.pet}
              </h3>
              <p>
                {modal === "diagnosis"
                  ? "기본기를 잘 쌓고 있어요. 다양한 입력 조건을 연습해 보세요."
                  : "첫 문제와 함께 성장의 여정을 시작해요."}
              </p>
            </div>
          </div>
          <Button
            className="primary"
            onClick={() => {
              if (modal !== "diagnosis") {
                setLevels({ ...levels, [modal]: 1 });
                setHero(modal);
              }
              setModal("");
            }}
          >
            {modal === "diagnosis" ? "확인" : "부화시키기"}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

export function DashboardPage() {
  const { problems } = useCatalog();
  const rSubs = useResource(() => judgeApi.getMySubmissions(0, 20), "dashboard-submissions");
  const rStudies = useResource(
    () => studyApi.getMyStudies({ status: "APPROVED", size: 3 }),
    "dashboard-studies",
  );

  const submissions = rSubs.data?.content ?? [];
  const practiceSubs = submissions.filter((s) => s.context === "PRACTICE");
  const solvedIds = new Set(
    submissions.filter((s) => s.verdict === "AC").map((s) => s.problemId),
  );
  const acCount = practiceSubs.filter((s) => s.verdict === "AC").length;
  const firstSub = submissions[0];

  return (
    <div className="container stack">
      <Heading
        title="오늘도 한 걸음, 더 성장해요"
        eyebrow="MY LEARNING"
        description="나의 학습 현황입니다."
      />
      {firstSub ? (
        <section className="card hero">
          <Pet size={96} />
          <div className="grow">
            <Badge tone="accent">CONTINUE</Badge>
            <h2 className="mt-3">멈췄던 곳에서 다시 시작해 볼까요?</h2>
            <p>{problems.find((p) => p.id === firstSub.problemId)?.title ?? firstSub.problemId}</p>
          </div>
          <Go className="secondary" href={`/problems/${firstSub.problemId}`}>
            이어서 풀기 <ArrowRight size={18} />
          </Go>
        </section>
      ) : null}
      <div className="dashboard-cols">
        <div className="stack">
          <Section title="추천 문제" href="/problems">
            {problems
              .filter((p) => !solvedIds.has(p.id))
              .slice(0, 4)
              .map((p) => (
                <Link className="activity-row" key={p.id} href={`/problems/${p.id}`}>
                  <BookOpen size={18} />
                  <div className="grow">
                    <b>{p.title}</b>
                    <p className="muted">{p.tags[0]?.name}</p>
                  </div>
                  <Badge>Lv.{p.difficulty}</Badge>
                </Link>
              ))}
            {!problems.filter((p) => !solvedIds.has(p.id)).length && (
              <Empty title="모든 문제를 풀었어요!" description="새로운 문제가 추가되면 알려드릴게요." />
            )}
          </Section>
          <Section title="학습 통계">
            <div className="grid-2">
              <div>
                <Flame />
                <p className="stat-number">{solvedIds.size}</p>
                <small>해결한 문제</small>
              </div>
              <div>
                <Trophy />
                <p className="stat-number">
                  {practiceSubs.length
                    ? Math.round((acCount / practiceSubs.length) * 100)
                    : 0}
                  %
                </p>
                <small>제출 정답률</small>
              </div>
            </div>
          </Section>
        </div>
        <div className="stack">
          <Section title="참여 중인 스터디" href="/studies">
            {rStudies.loading ? (
              <Loading />
            ) : rStudies.error ? (
              <ErrorState message={rStudies.error.message} retry={rStudies.retry} />
            ) : !rStudies.data?.content.length ? (
              <Empty title="참여 중인 스터디가 없습니다." />
            ) : (
              rStudies.data.content.map((s) => (
                <Link key={s.studyId} href={`/studies/${s.studyId}`} className="activity-row">
                  <Pet size={40} />
                  <div>
                    <h3>{s.name}</h3>
                    <p className="muted">{s.role}</p>
                  </div>
                </Link>
              ))
            )}
          </Section>
          <Section title="나의 성장">
            <div className="row">
              <Pet level={3} />
              <div className="grow">
                <h3>실력이 차곡차곡 쌓이는 중!</h3>
                <p className="muted">
                  전체 문제 중 {solvedIds.size} / {problems.length} 해결
                </p>
                <Progress
                  value={problems.length ? (solvedIds.size / problems.length) * 100 : 0}
                />
              </div>
            </div>
          </Section>
        </div>
        <Section title="최근 제출" href="/mypage">
          {rSubs.loading ? (
            <Loading />
          ) : rSubs.error ? (
            <ErrorState message={rSubs.error.message} retry={rSubs.retry} />
          ) : !submissions.length ? (
            <Empty title="아직 제출 내역이 없습니다." />
          ) : (
            <div className="stack-sm">
              {submissions.slice(0, 5).map((s) => (
                <Link key={s.submissionId} href={`/problems/${s.problemId}`} className="activity-row">
                  <BookOpen size={18} />
                  <div className="grow">
                    <b>{problems.find((p) => p.id === s.problemId)?.title ?? s.problemId}</b>
                    <p className="muted">
                      {VERDICT_LABEL[s.verdict ?? ""] ?? s.status} · {relativeDate(s.submittedAt)}
                    </p>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </Section>
      </div>
    </div>
  );
}
