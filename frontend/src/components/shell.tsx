"use client";
import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Moon, Sun, Settings, UserRound, LogOut } from "lucide-react";
import { CatalogProvider } from "@/services/catalog";
import { WorkspaceProvider } from "@/stores/workspace";
import { api, labels } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import type { Scenario } from "@/services/types";
import { Button, Modal, ToastProvider, useAction, useToast } from "./ui";
const nav = [
  ["/", "연습하기"],
  ["/dashboard", "학습 현황"],
  ["/problems", "문제"],
  ["/exams", "모의 코테"],
  ["/studies", "스터디"],
  ["/contests", "공개대회"],
];
function Frame({ children }: { children: ReactNode }) {
  const path = usePathname(),
    { data: user, loading: authLoading } = useResource(api.currentUser),
    action = useAction(),
    toast = useToast();
  const [theme, setTheme] = useState("light"),
    [demo, setDemo] = useState(false),
    [scenario, setScenario] = useState<Scenario>("AC");
  useEffect(() => {
    const sync = () =>
      setTheme(document.documentElement.dataset.theme || "light");
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
    return () => observer.disconnect();
  }, []);
  function toggle() {
    const next = theme === "light" ? "dark" : "light";
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("resolve:theme", next);
    } catch {
      toast("테마를 저장하지 못했습니다.");
    }
  }
  return (
    <>
      <a className="skip-link" href="#main">
        본문으로 이동
      </a>
      <header className="header">
        <div className="header-inner">
          <Link href="/" className="brand">
            Re:Solve<span className="pixel">↗</span>
          </Link>
          <nav className="nav" aria-label="주 메뉴">
            {nav.map(([href, title]) => (
              <Link
                key={href}
                href={href}
                className={
                  (href === "/" ? path === "/" : path.startsWith(href))
                    ? "active"
                    : ""
                }
              >
                {title}
              </Link>
            ))}
          </nav>
          <div className="header-actions">
            <Button
              className="icon-btn"
              aria-label="테마 전환"
              onClick={toggle}
            >
              {theme === "light" ? <Moon size={18} /> : <Sun size={18} />}
            </Button>
            <Button
              className="icon-btn"
              aria-label="데모 설정"
              onClick={() => setDemo(true)}
            >
              <Settings size={18} />
            </Button>
            <Link className="btn" href={user ? "/mypage" : "/login"}>
              <UserRound size={16} />
              <span className="profile-label">{user?.name || "로그인"}</span>
            </Link>
            {user && (
              <Button
                className="icon-btn"
                aria-label="로그아웃"
                onClick={() => action(api.logout, "로그아웃했습니다.", false)}
              >
                <LogOut size={16} />
              </Button>
            )}
          </div>
        </div>
      </header>
      <main id="main" data-ready={!authLoading}>
        <CatalogProvider>{children}</CatalogProvider>
      </main>
      <footer className="footer">
        <span className="pixel">Re:Solve · GROW WITH CODE</span>
        <span>하루 한 문제, 함께 자라는 실력.</span>
      </footer>
      <Modal
        open={demo}
        onOpenChange={setDemo}
        title="데모 설정"
        description="실제 코드를 실행하지 않습니다. 다음 모의 채점의 결과를 선택할 수 있어요."
      >
        <div className="stack">
          <label className="field">
            다음 채점 시나리오
            <select
              className="input"
              value={scenario}
              onChange={(e) => setScenario(e.target.value as Scenario)}
            >
              {["AC", "WA", "TLE", "CE", "MLE", "RE", "FAILED"].map((s) => (
                <option key={s} value={s}>
                  {labels[s]}
                </option>
              ))}
            </select>
          </label>
          <Button
            className="primary"
            onClick={() =>
              action(
                () => api.configure({ scenario }),
                `${labels[scenario]} 시나리오를 적용했습니다.`,
                false,
              )
            }
          >
            시나리오 적용
          </Button>
          <div className="row wrap">
            <Button
              onClick={() =>
                action(
                  () => api.configure({ simulateReadError: true }),
                  "조회 오류 재현 켜짐",
                  false,
                )
              }
            >
              조회 오류 재현
            </Button>
            <Button
              onClick={() =>
                action(
                  () => api.configure({ simulateReadError: false }),
                  "조회 오류 재현 꺼짐",
                  false,
                )
              }
            >
              조회 오류 해제
            </Button>
          </div>
          <details>
            <summary>목업 데이터 초기화</summary>
            <p className="muted">
              생성한 데이터와 코드 초안이 삭제되고 데모 사용자로 돌아갑니다.
            </p>
            <Button
              className="danger"
              onClick={() =>
                action(
                  async () => {
                    await api.reset();
                    for (const k of Object.keys(localStorage))
                      if (k.startsWith("resolve:draft:"))
                        localStorage.removeItem(k);
                    location.reload();
                  },
                  undefined,
                  false,
                )
              }
            >
              전체 초기화
            </Button>
          </details>
        </div>
      </Modal>
    </>
  );
}
export function Shell({ children }: { children: ReactNode }) {
  return (
    <WorkspaceProvider>
      <ToastProvider>
        <Frame>{children}</Frame>
      </ToastProvider>
    </WorkspaceProvider>
  );
}
