import { test, expect, type Page } from "@playwright/test";
import { EXAM_ID, INSTANT_STUDY_ID, member, mockApi } from "./api-fixture";

const editor = (page: Page) => page.getByRole("textbox", { name: "코드 편집기" });

test("문제 화면의 질문·풀이 노트 탭은 각각 준비 중 안내를 표시한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/problems/1001");
  await expect(editor(page)).toBeVisible();

  await page.getByRole("button", { name: "질문", exact: true }).click();
  await expect(page.getByRole("heading", { name: "커뮤니티 게시판 준비 중" })).toBeVisible();
  await page.getByRole("button", { name: "풀이 노트", exact: true }).click();
  await expect(page.getByRole("heading", { name: "풀이 노트 준비 중" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "커뮤니티 게시판 준비 중" })).toHaveCount(0);
});

test("마이페이지에서 닉네임을 저장하면 성공을 알리고 GitHub 아이디를 유지하며 제출 기록 탭은 제출 표를 보여 준다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/mypage");
  await expect(page.getByRole("heading", { name: `${member.nickname}님의 마이페이지` })).toBeVisible();

  await page.getByLabel("닉네임").fill("성장하는 개발자");
  const saved = page.waitForRequest((request) => request.method() === "PATCH"
    && new URL(request.url()).pathname === "/api/v1/members/me");
  await page.getByRole("button", { name: "프로필 저장", exact: true }).click();
  expect((await saved).postDataJSON()).toEqual({ nickname: "성장하는 개발자" });
  await expect(page.locator(".toast")).toHaveText("프로필을 저장했습니다.");
  await expect(page.getByRole("heading", { name: "성장하는 개발자님의 마이페이지" })).toBeVisible();
  await expect(page.getByText(`@${member.githubLogin}`, { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "제출 기록", exact: true }).click();
  const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: "제출 5000 코드 및 결과 보기" }) });
  await expect(row).toBeVisible();
  await expect(row.getByText("정답", { exact: true })).toBeVisible();
  await expect(row.getByRole("cell", { name: "Java", exact: true })).toBeVisible();
});

test("즉시 가입 스터디에 가입하면 소개·구성원·주간 문제집 탭을 오류 없이 보여 준다", async ({ page }) => {
  await mockApi(page);
  await page.goto(`/studies/${INSTANT_STUDY_ID}`);
  await expect(page.getByRole("heading", { name: "즉시 가입 스터디" })).toBeVisible();
  await page.getByRole("button", { name: "가입", exact: true }).click();
  await expect(page.getByRole("button", { name: "탈퇴", exact: true })).toBeVisible();

  const failure = page.getByRole("alert").filter({ hasText: "불러오지 못했어요" });
  await expect(page.getByText("가입 방식", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "구성원", exact: true }).click();
  await expect(page.getByRole("heading", { name: "구성원 (1)" })).toBeVisible();
  // fixture의 유일한 구성원은 닉네임과 역할 배지가 모두 "스터디장"이다.
  await expect(page.locator(".detail-grid section").first().getByText("스터디장", { exact: true })).toHaveCount(2);
  await expect(failure).toHaveCount(0);

  await page.getByRole("button", { name: "주간 문제집", exact: true }).click();
  await expect(page.getByRole("heading", { name: "진행 중인 문제집이 없어요" })).toBeVisible();
  await expect(failure).toHaveCount(0);
});

test("1440px 시험 응시 화면은 라이트·다크 테마 모두 남은 시간·문제 목록·편집기를 보여 준다", async ({ page }) => {
  await mockApi(page);
  await page.goto(`/exams/${EXAM_ID}`);
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await page.getByRole("button", { name: "응시 시작", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/room$`));

  const html = page.locator("html");
  const editorBackground = () => page.locator(".monaco-editor-background").first()
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  let lightBackground = "";
  for (const theme of ["light", "dark"]) {
    if (theme === "dark") await page.getByRole("button", { name: "테마 전환", exact: true }).click();
    await expect(html).toHaveAttribute("data-theme", theme);
    await expect(page.getByLabel("남은 시간")).toHaveText(/\d{2}:\d{2}/);
    await expect(page.getByRole("button", { name: /문제 1/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /문제 2/ })).toBeVisible();
    await expect(page.getByRole("heading", { name: "고정 회차 배열 합" })).toBeVisible();
    await expect(editor(page)).toBeVisible();
    await expect(page.getByRole("button", { name: "응시 종료", exact: true })).toBeVisible();
    // 편집기도 테마를 따라가야 어두운 화면에서 밝은 편집기가 튀지 않는다.
    if (theme === "light") lightBackground = await editorBackground();
    else await expect.poll(editorBackground).not.toBe(lightBackground);
  }
});

// GitHub 로그인이 곧 가입이라 별도 회원가입 화면 없이 로그인 화면이 돌아갈 경로를 OAuth에 넘긴다 (정책 및 상태 §1 가입·로그인).
test("로그인 화면에서 GitHub 로그인을 누르면 돌아갈 경로를 들고 GitHub 인증으로 이동한다", async ({ page }) => {
  let oauth: URL | null = null;
  await mockApi(page, {
    loggedIn: false,
    handle: async (route, path) => {
      if (path !== "/api/v1/auth/github") return false;
      oauth = new URL(route.request().url());
      // 실제 GitHub으로 나가지 않도록 인증 시작 요청에서 멈춘다.
      await route.fulfill({ status: 200, contentType: "text/html", body: "<p>github</p>" });
      return true;
    },
  });
  await page.goto("/login?returnTo=%2Fstudies");
  // 인증 복원이 끝나기 전에는 화면이 바뀔 수 있어 준비 완료 뒤에 누른다.
  await expect(page.locator("main")).toHaveAttribute("data-ready", "true");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fstudies$/);
  await expect(page.getByRole("heading", { name: "다시 만나서 반가워요" })).toBeVisible();
  await page.getByRole("button", { name: "GitHub으로 로그인", exact: true }).click();
  await expect.poll(() => oauth?.searchParams.get("redirectPath")).toBe("/studies");
});
