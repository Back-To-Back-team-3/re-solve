"use client";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import { useWorkspace } from "@/stores/workspace";
import { Pet } from "@/components/pet";
import {
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
import { SubmissionList } from "./problem-list";
export function safeReturn(value: string | null) {
  return value?.startsWith("/") &&
    !value.startsWith("//") &&
    !value.includes("\\")
    ? value
    : "/dashboard";
}
export function AuthPage({ signup = false }: { signup?: boolean }) {
  const router = useRouter(),
    params = useSearchParams(),
    action = useAction(),
    [busy, setBusy] = useState(false);
  return (
    <div className="auth-layout">
      <div className="auth-art">
        <p className="pixel">HELLO, NEW CHALLENGE!</p>
        <Pet size={180} level={2} />
        <h2>
          다시 풀고,
          <br />
          함께 자라요.
        </h2>
        <p>오늘의 한 문제가 내일의 자신감으로.</p>
      </div>
      <form
        className="card stack"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          const form = e.currentTarget,
            f = new FormData(form);
          await action(
            async () => {
              const email = String(f.get("email"));
              if (signup) await api.signup(email, String(f.get("name")));
              else await api.login(email);
              form.reset();
              router.push(safeReturn(params.get("returnTo")));
            },
            signup ? "데모 계정을 만들었습니다." : "데모 로그인했습니다.",
            false,
          );
          setBusy(false);
        }}
      >
        <Heading
          title={signup ? "회원가입" : "다시 만나서 반가워요"}
          description="실제 인증 없이 체험하는 데모 계정입니다."
        />
        {signup && (
          <label className="field">
            닉네임
            <input
              name="name"
              className="input"
              required
              minLength={2}
              maxLength={20}
              autoComplete="nickname"
            />
          </label>
        )}
        <label className="field">
          이메일
          <input
            name="email"
            type="email"
            className="input"
            required
            autoComplete="email"
            placeholder="player@resolve.demo"
          />
        </label>
        <label className="field">
          비밀번호
          <input
            type="password"
            className="input"
            required
            minLength={4}
            autoComplete={signup ? "new-password" : "current-password"}
          />
        </label>
        <p className="notice">
          입력한 비밀번호는 저장하거나 전송하지 않습니다. 데모 로그인은 이메일로
          사용자를 구분합니다.
        </p>
        <Button className="primary" disabled={busy}>
          {signup ? "데모 회원가입" : "데모 로그인"}
        </Button>
        <Go
          href={`${signup ? "/login" : "/signup"}?returnTo=${encodeURIComponent(safeReturn(params.get("returnTo")))}`}
        >
          {signup ? "이미 계정이 있어요" : "처음이라면, 회원가입"}
        </Go>
      </form>
    </div>
  );
}
export function MyPage() {
  const r = useResource(api.snapshot),
    action = useAction(),
    [tab, setTab] = useState("profile"),
    fontSize = useWorkspace((s) => s.fontSize),
    setFontSize = useWorkspace((s) => s.setFontSize);
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
    u = db.users.find((x) => x.id === db.currentUserId);
  if (!u)
    return (
      <div className="container">
        <Empty
          title="로그인 후 이용해 주세요"
          action={<Go href="/login?returnTo=%2Fmypage">로그인</Go>}
        />
      </div>
    );
  return (
    <div className="container stack">
      <Heading
        title={`${u.name}님의 마이페이지`}
        eyebrow="MY PROFILE"
        description="학습 기록과 설정을 한곳에서 관리하세요."
      />
      <div className="card row">
        <Pet size={90} />
        <div>
          <h2>{u.name}</h2>
          <p>{u.email}</p>
          <p className="muted">{u.goal}</p>
        </div>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { id: "profile", label: "프로필" },
          { id: "submissions", label: "제출 기록" },
          { id: "activity", label: "참여 활동" },
          { id: "settings", label: "설정" },
        ]}
      />
      {tab === "profile" && (
        <form
          className="card form-card full stack"
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            action(
              () =>
                api.profile({
                  name: String(f.get("name")).trim(),
                  goal: String(f.get("goal")),
                }),
              "프로필을 저장했습니다.",
            );
          }}
        >
          <label className="field">
            닉네임
            <input
              name="name"
              className="input"
              defaultValue={u.name}
              minLength={2}
              maxLength={20}
              required
            />
          </label>
          <label className="field">
            나의 목표
            <input
              name="goal"
              className="input"
              defaultValue={u.goal}
              maxLength={100}
            />
          </label>
          <Button className="primary">프로필 저장</Button>
        </form>
      )}
      {tab === "submissions" && (
        <Section title="최근 제출">
          <SubmissionList
            items={db.submissions
              .filter((s) => s.userId === u.id && s.kind === "submit")
              .slice()
              .sort((a, b) => b.createdAt - a.createdAt)}
          />
        </Section>
      )}
      {tab === "activity" && (
        <div className="grid-2">
          <Section title="참여 스터디">
            {db.studies
              .filter(
                (s) => s.members.includes(u.id) || s.applicants.includes(u.id),
              )
              .map((s) => (
                <Go key={s.id} href={`/studies/${s.id}`}>
                  {s.title}
                </Go>
              ))}
          </Section>
          <Section title="참여 시험">
            <div className="stack-sm">
              {db.exams
                .filter((e) => e.participants.includes(u.id))
                .map((e) => (
                  <Go key={e.id} href={`/exams/${e.id}`}>
                    {e.title}
                  </Go>
                ))}
            </div>
          </Section>
        </div>
      )}
      {tab === "settings" && (
        <Section title="화면과 에디터 설정">
          <div className="form-grid">
            <label className="field">
              테마
              <select
                className="input"
                defaultValue={document.documentElement.dataset.theme || "light"}
                onChange={(e) => {
                  document.documentElement.dataset.theme = e.target.value;
                  try {
                    localStorage.setItem("resolve:theme", e.target.value);
                  } catch {}
                }}
              >
                <option value="light">라이트</option>
                <option value="dark">다크</option>
              </select>
            </label>
            <label className="field">
              에디터 글자 크기
              <select
                className="input"
                value={fontSize}
                onChange={(e) => {
                  const size = Number(e.target.value);
                  setFontSize(size);
                  try {
                    const prev = JSON.parse(
                      localStorage.getItem("resolve:editor") || "{}",
                    );
                    localStorage.setItem(
                      "resolve:editor",
                      JSON.stringify({ ...prev, fontSize: size }),
                    );
                  } catch {}
                }}
              >
                {[12, 14, 16, 18, 20, 22].map((n) => (
                  <option key={n} value={n}>
                    {n}px
                  </option>
                ))}
              </select>
            </label>
          </div>
        </Section>
      )}
    </div>
  );
}
