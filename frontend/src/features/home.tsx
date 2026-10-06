"use client";
import { useState } from "react";
import Link from "next/link";
import {
  ArrowRight,
  Check,
  Flame,
  BookOpen,
  Trophy,
  CalendarDays,
} from "lucide-react";
import { Pet } from "@/components/pet";
import {
  Badge,
  Button,
  Go,
  Heading,
  Section,
  Progress,
  Loading,
  ErrorState,
  Modal,
  useToast,
} from "@/components/ui";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import { useCatalog } from "@/services/catalog";
import { ProblemTable, SubmissionList } from "./problem-list";
export function HomePage() {
  const { tags, problems } = useCatalog();
  const r = useResource(api.snapshot),
    toast = useToast();
  const [hero, setHero] = useState("hash"),
    [screen, setScreen] = useState("pet"),
    [modal, setModal] = useState(""),
    [levels, setLevels] = useState<Record<string, number>>({});
  const current = tags.find((t) => t.id === hero)!;
  const petLevel = levels[hero] ?? current.level;
  const stages = ["알", "아기", "어린이", "어른", "마스터"];
  const needed = [0, 5, 6, 5, 0][petLevel];
  const goal = [0, 60, 70, 65, 0][petLevel];
  const speech =
    petLevel === 0
      ? "톡톡… 알을 깨워 줄래요? 진단을 보거나 아기로 부화시켜 주세요."
      : petLevel === 4
        ? "왕관 어때요? 그래도 가끔 난이도 3 문제 하나씩은 주세요!"
        : current.rate < goal
          ? "배고파요… 힌트 없이 풀어 주면 더 든든해요."
          : `냠냠! ${needed - current.ac}문제만 더 먹으면 ${stages[petLevel + 1]}(으)로 자라요!`;
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
  const submissions = r.data!.submissions.filter(
    (s) => s.userId === r.data!.currentUserId && !s.examId,
  );
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
            <div className="speech">
              <b>
                {petLevel === 0
                  ? "??? (알)"
                  : `${current.pet} · ${stages[petLevel]}`}
              </b>
              <p>“{speech}”</p>
            </div>
            <div className="row wrap">
              <Go className="secondary" href={`/problems?tag=${hero}`}>
                문제 풀러 가기 <ArrowRight size={16} />
              </Go>
              <Button onClick={() => setModal("diagnosis")}>
                내 실력 진단
              </Button>
            </div>
          </div>
          <div className="device">
            <span className="pixel">RE:GOTCHI</span>
            <div className="lcd">
              <div className="row between full">
                <span className="pixel">{current.pet}</span>
                <span>Lv.{levels[hero] ?? current.level}</span>
              </div>
              {screen === "pet" ? (
                <Pet
                  level={levels[hero] ?? current.level}
                  size={100}
                  label={current.pet}
                />
              ) : (
                <div className="stack-sm">
                  <b>
                    {screen === "status" ? "알고리즘 성장 상태" : "오늘의 응원"}
                  </b>
                  <span>
                    {screen === "status"
                      ? `정답률 ${current.rate}% · ${current.ac}회 해결`
                      : "조금씩, 꾸준히 잘하고 있어!"}
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
        </section>
        <Section title="오늘의 돌보기">
          <div className="stack">
            <Badge tone="accent">DAILY</Badge>
            <p className="muted">작은 습관이 모여, 더 큰 실력이 돼요.</p>
            {[
              ["문제 한 개 풀기", "펫에게 맛있는 한 끼"],
              ["틀린 문제 다시 보기", "약한 부분을 단단하게"],
              ["풀이 노트 남기기", "오늘 배운 것을 기억해요"],
            ].map(([a, b], i) => (
              <Link
                href={i === 0 ? "/problems" : "/problems/1002"}
                className="quest"
                key={a}
              >
                <span className="check-box">
                  {i === 0 && <Check size={18} />}
                </span>
                <div>
                  <b>{a}</b>
                  <p className="muted">{b}</p>
                </div>
              </Link>
            ))}
            <Progress value={33} />
            <small>1 / 3 완료</small>
          </div>
        </Section>
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
                    {level === 0
                      ? "EGG"
                      : level === 4
                        ? "MASTER"
                        : `LEVEL ${level}`}
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
                  {level === 0
                    ? "새로운 가능성이 잠들어 있어요."
                    : `${t.ac}문제 해결 · 정답률 ${t.rate}%`}
                </p>
                <Progress value={level === 0 ? 0 : level * 23} />
                <div className="row actions">
                  {level === 0 ? (
                    <Button
                      className="primary full"
                      onClick={() => setModal(t.id)}
                    >
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
          <ProblemTable items={problems.slice(0, 4)} db={r.data} />
        </Section>
        <Section title="성장 일기">
          {[
            ["TODAY", "해시몽이 레벨 2가 되었어요!"],
            ["YESTERDAY", "스택/큐의 첫 문제를 해결했어요."],
            ["3 DAYS AGO", "연결이가 마스터로 성장했어요."],
          ].map(([a, b]) => (
            <div className="activity-row" key={a}>
              <Pet level={2} size={34} />
              <div>
                <small className="pixel">{a}</small>
                <p>{b}</p>
              </div>
            </div>
          ))}
        </Section>
      </div>
      <Section title="최근 제출" href="/mypage">
        <SubmissionList
          items={submissions.slice().sort((a, b) => b.createdAt - a.createdAt)}
        />
      </Section>
      <Modal
        open={!!modal}
        onOpenChange={() => setModal("")}
        title={
          modal === "diagnosis" ? "알고리즘 성장 진단" : "새 친구를 만나볼까요?"
        }
        description={
          modal === "diagnosis"
            ? "진단은 고정된 예시 결과입니다. 실제 풀이 분석은 진행하지 않습니다."
            : "부화는 이 화면의 상태만 바꿉니다. 문제는 언제든 자유롭게 풀 수 있어요."
        }
      >
        <div className="stack">
          <div className="row">
            <Pet level={modal === "diagnosis" ? current.level : 0} />
            <div>
              <h3>
                {modal === "diagnosis"
                  ? `${current.pet}의 다음 목표`
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
            {" "}
            {modal === "diagnosis" ? "확인" : "부화시키기"}
          </Button>
        </div>
      </Modal>
    </div>
  );
}
export function DashboardPage() {
  const { tags, problems } = useCatalog();
  const r = useResource(api.snapshot);
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
    mine = db.submissions.filter(
      (s) => s.userId === db.currentUserId && s.kind === "submit" && !s.examId,
    ),
    solved = new Set(
      mine.filter((s) => s.verdict === "AC").map((s) => s.problemId),
    );
  return (
    <div className="container stack">
      <Heading
        title="오늘도 한 걸음, 더 성장해요"
        eyebrow="MY LEARNING"
        description={`${db.users.find((u) => u.id === db.currentUserId)?.name || "방문자"}님의 학습 현황입니다.`}
      />
      <section className="card hero">
        <Pet size={96} />
        <div className="grow">
          <Badge tone="accent">CONTINUE</Badge>
          <h2 style={{ marginTop: 12 }}>멈췄던 곳에서 다시 시작해 볼까요?</h2>
          <p>올바른 괄호 · 스택/큐 · Lv. 1</p>
        </div>
        <Go className="secondary" href="/problems/1002">
          이어서 풀기 <ArrowRight size={18} />
        </Go>
      </section>
      <div className="dashboard-cols">
        <div className="stack">
          <Section title="추천 문제" href="/problems">
            {problems
              .filter((p) => !solved.has(p.id))
              .slice(0, 4)
              .map((p) => (
                <Link
                  className="activity-row"
                  key={p.id}
                  href={`/problems/${p.id}`}
                >
                  <BookOpen size={18} />
                  <div className="grow">
                    <b>{p.title}</b>
                    <p className="muted">
                      {tags.find((t) => t.id === p.tagId)?.name}
                    </p>
                  </div>
                  <Badge>Lv.{p.difficulty}</Badge>
                </Link>
              ))}
          </Section>
          <Section title="학습 통계">
            <div className="grid-2">
              <div>
                <Flame />
                <p className="stat-number">{solved.size}</p>
                <small>해결한 문제</small>
              </div>
              <div>
                <Trophy />
                <p className="stat-number">
                  {mine.length
                    ? Math.round(
                        (mine.filter((s) => s.verdict === "AC").length /
                          mine.length) *
                          100,
                      )
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
            {db.studies
              .filter((s) => s.members.includes(db.currentUserId || ""))
              .map((s) => (
                <Link
                  key={s.id}
                  href={`/studies/${s.id}`}
                  className="activity-row"
                >
                  <Pet size={40} />
                  <div>
                    <h3>{s.title}</h3>
                    <p className="muted">
                      {s.members.length}명 · {s.goal}
                    </p>
                  </div>
                </Link>
              ))}
          </Section>
          <Section title="다가오는 모의 코테" href="/exams">
            {db.exams
              .filter(
                (e) =>
                  e.participants.includes(db.currentUserId || "") &&
                  e.status !== "ended",
              )
              .slice(0, 3)
              .map((e) => (
                <Link
                  key={e.id}
                  href={`/exams/${e.id}`}
                  className="activity-row"
                >
                  <CalendarDays />
                  <div>
                    <b>{e.title}</b>
                    <p className="muted">
                      {e.duration}분 · {e.problemIds.length}문제
                    </p>
                  </div>
                </Link>
              ))}
          </Section>
          <Section title="나의 성장">
            <div className="row">
              <Pet level={3} />
              <div className="grow">
                <h3>실력이 차곡차곡 쌓이는 중!</h3>
                <p className="muted">
                  전체 문제 중 {solved.size} / {problems.length} 해결
                </p>
                <Progress value={(solved.size / problems.length) * 100} />
              </div>
            </div>
          </Section>
        </div>
        <Section title="최근 제출" href="/mypage">
          <SubmissionList
            items={mine.slice().sort((a, b) => b.createdAt - a.createdAt)}
          />
        </Section>
      </div>
    </div>
  );
}
