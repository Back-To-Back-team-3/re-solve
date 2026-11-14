"use client";
import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { authApi, judgeApi, memberApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { THEME_STORAGE_KEY } from "@/services/storage-keys";
import { ProblemSubmissionList } from "@/features/problem-list";
import { NotificationPreferencesSection } from "@/features/account-notifications";
import { SkillPage } from "@/features/account-skill";
import { useWorkspace } from "@/stores/workspace";
import { Pet } from "@/components/pet";
import { GithubIntegrationSection } from "@/features/account-github";
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
export function CallbackPage() {
  const router = useRouter();
  const params = useSearchParams();
  const setMember = useWorkspace((s) => s.setMember);
  const setAccessToken = useWorkspace((s) => s.setAccessToken);
  const setAuthReady = useWorkspace((s) => s.setAuthReady);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const loginCode = params.get("loginCode");
    const redirectPath = safeReturn(params.get("redirectPath"));
    if (!loginCode) {
      setError("유효하지 않은 접근입니다.");
      return;
    }
    let isNew = false;
    authApi
      .exchangeToken(loginCode)
      .then(({ accessToken, member }) => {
        setAccessToken(accessToken);
        isNew = member.isNewMember;
        return memberApi.getMe();
      })
      .then((fullMember) => {
        setMember(fullMember);
        setAuthReady(true);
        router.push(isNew ? "/dashboard?newMember=true" : redirectPath);
      })
      .catch(() => setError("로그인 처리 중 오류가 발생했습니다."));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (error)
    return (
      <div className="container stack">
        <p>{error}</p>
        <Go href="/login">로그인 페이지로</Go>
      </div>
    );
  return <Loading />;
}

export function safeReturn(value: string | null) {
  return value?.startsWith("/") &&
    !value.startsWith("//") &&
    !value.includes("\\")
    ? value
    : "/dashboard";
}
export function AuthPage() {
  const params = useSearchParams();
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
      <div className="card stack">
        <Heading
          title="다시 만나서 반가워요"
          description="GitHub 계정으로 로그인합니다."
        />
        <Button
          className="primary"
          onClick={() => {
            const returnTo = params.get("returnTo");
            // OAuth 외부 리다이렉트 — router.push는 CSR 내부 라우팅이므로 사용 불가
            window.location.href = returnTo
              ? `/api/v1/auth/github?redirectPath=${encodeURIComponent(safeReturn(returnTo))}`
              : "/api/v1/auth/github";
          }}
        >
          GitHub으로 로그인
        </Button>
      </div>
    </div>
  );
}
function MySubmissions() {
  const r = useResource(() => judgeApi.getMySubmissions(0, 20), "mypage-submissions");
  if (r.loading && !r.data) return <Loading />;
  if (r.error && !r.data) return <ErrorState message={r.error.message} retry={r.retry} />;
  return <ProblemSubmissionList items={r.data?.content ?? []} />;
}

export function MyPage() {
  const member = useWorkspace((s) => s.member),
    setMember = useWorkspace((s) => s.setMember),
    clearMember = useWorkspace((s) => s.clearMember),
    action = useAction(),
    router = useRouter(),
    [tab, setTab] = useState("profile"),
    fontSize = useWorkspace((s) => s.fontSize),
    setFontSize = useWorkspace((s) => s.setFontSize),
    [theme, setTheme] = useState("light");

  useEffect(() => {
    setTheme(document.documentElement.dataset.theme ?? "light");
  }, []);

  if (!member)
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
        title={`${member.nickname}님의 마이페이지`}
        eyebrow="MY PROFILE"
        description="학습 기록과 설정을 한곳에서 관리하세요."
      />
      <div className="card row">
        <Pet size={90} />
        <div>
          <h2>{member.nickname}</h2>
          {member.email && <p>{member.email}</p>}
          <p className="muted">@{member.githubLogin}</p>
        </div>
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { id: "profile", label: "프로필" },
          { id: "skill", label: "스킬" },
          { id: "submissions", label: "제출 기록" },
          { id: "github", label: "GitHub 연동" },
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
              async () => {
                const updated = await memberApi.updateMe({
                  nickname: String(f.get("nickname")).trim(),
                });
                setMember({ ...member, nickname: updated.nickname });
              },
              "프로필을 저장했습니다.",
            );
          }}
        >
          <label className="field">
            닉네임
            <input
              name="nickname"
              className="input"
              defaultValue={member.nickname}
              minLength={2}
              maxLength={50}
              required
            />
          </label>
          <Button className="primary">프로필 저장</Button>
          <Button
            type="button"
            onClick={() =>
              action(
                async () => {
                  await authApi.logout();
                  clearMember();
                  router.push("/login");
                },
                "로그아웃했습니다.",
              )
            }
          >
            로그아웃
          </Button>
        </form>
      )}
      {tab === "skill" && <SkillPage />}
      {tab === "submissions" && (
        <Section title="최근 제출">
          <MySubmissions />
        </Section>
      )}
      {tab === "github" && <GithubIntegrationSection />}
      {tab === "settings" && (
        <div className="stack">
          <Section title="화면과 에디터 설정">
            <div className="form-grid">
              <label className="field">
                테마
                <select
                  className="input"
                  value={theme}
                  onChange={(e) => {
                    setTheme(e.target.value);
                    document.documentElement.dataset.theme = e.target.value;
                    try {
                      localStorage.setItem(THEME_STORAGE_KEY, e.target.value);
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
                        localStorage.getItem("re-solve:settings:editor") ?? "{}",
                      );
                      localStorage.setItem(
                        "re-solve:settings:editor",
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
          <NotificationPreferencesSection />
        </div>
      )}
    </div>
  );
}

