"use client";
import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Moon, Sun, UserRound, LogOut } from "lucide-react";
import { CatalogProvider } from "@/services/catalog";
import { WorkspaceProvider, useWorkspace } from "@/stores/workspace";
import { authApi } from "@/services/api";
import { Button, ToastProvider, useToast } from "./ui";
import { NotificationBell } from "@/features/notifications";
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
    toast = useToast(),
    member = useWorkspace((s) => s.member);
  const router = useRouter();
  const authReady = useWorkspace((s) => s.authReady);
  const clearMember = useWorkspace((s) => s.clearMember);
  const [theme, setTheme] = useState("light");
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
            {member?.role === "ADMIN" && (
              <Link
                href="/admin/problems"
                className={path.startsWith("/admin") ? "active" : ""}
              >
                문제 관리
              </Link>
            )}
          </nav>
          <div className="header-actions">
            <Button
              className="icon-btn"
              aria-label="테마 전환"
              onClick={toggle}
            >
              {theme === "light" ? <Moon size={18} /> : <Sun size={18} />}
            </Button>
            <Link className="btn" href={member ? "/mypage" : "/login"}>
              <UserRound size={16} />
              <span className="profile-label">{member?.nickname || "로그인"}</span>
            </Link>
            {member && <NotificationBell />}
            {member && (
              <Button
                className="icon-btn"
                aria-label="로그아웃"
                onClick={async () => {
                  try {
                    await authApi.logout();
                  } catch (e) {
                    // UNAUTHORIZED는 서버 세션이 이미 무효라 로컬만 정리한다. 그 외 실패는 세션이 남아 있을 수 있어 유지한다.
                    if ((e as { code?: string }).code !== "UNAUTHORIZED") {
                      toast("로그아웃하지 못했습니다. 다시 시도해 주세요.");
                      return;
                    }
                  }
                  clearMember();
                  router.push("/login");
                }}
              >
                <LogOut size={16} />
              </Button>
            )}
          </div>
        </div>
      </header>
      <main id="main" data-ready={authReady}>
        {authReady && member !== null ? (
          <CatalogProvider>{children}</CatalogProvider>
        ) : (
          children
        )}
      </main>
      <footer className="footer">
        <span className="pixel">Re:Solve · GROW WITH CODE</span>
        <span>하루 한 문제, 함께 자라는 실력.</span>
      </footer>
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
