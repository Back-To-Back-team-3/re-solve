import { test, expect, type Locator, type Page } from "@playwright/test";
import { EXAM_ID, INSTANT_STUDY_ID, mockApi } from "./api-fixture";

/**
 * `src/app/**\/page.tsx`의 사용자 화면. 동적 경로는 api-fixture가 응답하는 ID로 채운다.
 * 제외한 경로:
 * - `/admin/**`: ADMIN 권한 화면은 admin-* 스펙이 전용 fixture로 확인한다.
 * - `/auth/callback`: GitHub OAuth가 발급한 loginCode가 있어야 열린다.
 * - `/exams/[id]/room`: 응시 시작 후에만 열리므로 details·flows 스펙이 입실 흐름으로 확인한다.
 * - `/studies/[id]/assignments/[aid]`: fixture의 문제집 목록이 비어 있어 열 수 있는 문제집이 없다.
 */
const routes: { path: string; ready: (page: Page) => Locator }[] = [
  { path: "/", ready: (p) => p.getByRole("heading", { name: /나만의 알고리즘 펫/ }) },
  { path: "/dashboard", ready: (p) => p.getByRole("heading", { name: "오늘도 한 걸음, 더 성장해요" }) },
  { path: "/problems", ready: (p) => p.getByRole("link", { name: "고정 회차 배열 합" }) },
  { path: "/problems/1001", ready: (p) => p.getByRole("textbox", { name: "코드 편집기" }) },
  { path: "/studies", ready: (p) => p.getByRole("heading", { name: "함께라서 더 멀리, 스터디" }) },
  { path: "/studies/new", ready: (p) => p.getByRole("heading", { name: "스터디 만들기" }) },
  { path: `/studies/${INSTANT_STUDY_ID}`, ready: (p) => p.getByRole("heading", { name: "즉시 가입 스터디" }) },
  { path: "/exams", ready: (p) => p.getByRole("heading", { name: "모의 코딩테스트" }) },
  { path: `/exams/new?studyId=${INSTANT_STUDY_ID}`, ready: (p) => p.getByRole("heading", { name: "모의 코테 만들기" }) },
  { path: `/exams/${EXAM_ID}`, ready: (p) => p.getByRole("heading", { name: "주간 모의 코테" }) },
  { path: `/exams/${EXAM_ID}/result`, ready: (p) => p.getByRole("heading", { name: "도전의 기록" }) },
  { path: "/contests", ready: (p) => p.getByRole("heading", { name: "공개대회 준비 중" }) },
  { path: `/contests/${EXAM_ID}`, ready: (p) => p.getByRole("heading", { name: "주간 모의 코테" }) },
  { path: "/submissions/5000", ready: (p) => p.getByRole("heading", { name: "제출 #5000" }) },
  { path: "/mypage", ready: (p) => p.getByRole("heading", { name: "테스터님의 마이페이지" }) },
  { path: "/login", ready: (p) => p.getByRole("heading", { name: "다시 만나서 반가워요" }) },
  { path: "/signup", ready: (p) => p.getByRole("heading", { name: "다시 만나서 반가워요" }) },
];

for (const theme of ["light", "dark"])
  test(`1440px ${theme} 테마로 모든 사용자 화면을 열면 오류 상태 없이 가로 넘침 없이 표시한다`, async ({ page }) => {
    test.setTimeout(240000);
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.addInitScript((t) => {
      try {
        localStorage.setItem("resolve:theme", t);
      } catch {}
    }, theme);
    await mockApi(page);
    for (const { path, ready } of routes) {
      await page.goto(path);
      await expect(page.locator("main"), path).toHaveAttribute("data-ready", "true");
      await expect(ready(page), path).toBeVisible();
      await expect(page.getByText("LOADING...", { exact: true }), path).toHaveCount(0);
      await expect(page.getByRole("alert").filter({ hasText: "불러오지 못했어요" }), path).toHaveCount(0);
      // Next.js 개발 서버의 오류 오버레이는 shadow DOM 안에 그려지며 Playwright CSS 선택자가 이를 관통한다.
      await expect(page.locator("[data-nextjs-dialog]"), path).toHaveCount(0);
      await expect(page.locator("html"), path).toHaveAttribute("data-theme", theme);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
      expect(overflow, `${path} 가로 넘침`).toBe(false);
    }
    expect(errors).toEqual([]);
  });
