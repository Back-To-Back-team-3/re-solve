import { expect, test, type Page } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

async function mockExam(page: Page) {
  await mockPracticePage(page);
  const now = new Date().toISOString(), endsAt = new Date(Date.now() + 3600000).toISOString();
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", enabled: true }, { language: "python3", enabled: true },
  ] } } }));
  await page.route("**/api/v1/exams/42/problems", (route) => route.fulfill({ json: { success: true, data: {
    examId: "42", serverNow: now, personalEndsAt: endsAt,
    problems: [{ problemId: "1001", problemRevisionId: "9001", revisionNumber: 2, title: "시험 문제", body: "본문",
      score: "100.00", functionSpec: problemDetail.functionSpec, executionLimits: problemDetail.executionLimits,
      sampleTests: problemDetail.samples, imageIds: [] }],
  } } }));
  await page.route("**/api/v1/exams/42/drafts/1001", (route) => route.fulfill({ json: { success: true, data: {
    applied: true, serverSeq: route.request().postDataJSON().seq, savedAt: now, personalEndsAt: endsAt, serverNow: now,
  } } }));
  return now;
}

test("시험 제출 전송은 언어 전환 후에도 중복되지 않고 실패 뒤 재제출할 수 있다", async ({ page }) => {
  const now = await mockExam(page);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let posts = 0;
  await page.route("**/api/v1/exams/42/submissions", async (route) => {
    posts++;
    if (posts === 1) {
      await held;
      return route.fulfill({ status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "시험 접수 실패" } } });
    }
    return route.fulfill({ status: 202, json: { success: true, data: {
      examSubmissionId: "99", status: "RECEIVED", receivedAt: now, draftSeq: "1",
    } } });
  });
  try {
    await page.goto("/exams/42/room");
    await page.getByLabel("코드 언어").selectOption("java");
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect.poll(() => posts).toBe(1);
    await page.getByLabel("코드 언어").selectOption("python3");
    await page.getByLabel("코드 언어").selectOption("java");
    await expect(page.getByRole("button", { name: "채점 중…", exact: true })).toBeDisabled();
    await expect(page.getByText("제출 접수 중…", { exact: true })).toBeVisible();
    expect(posts).toBe(1);
    release();
    await expect(page.getByText("시험 접수 실패", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
    await expect(page.getByText("시험 접수 실패", { exact: true })).toHaveCount(0);
    expect(posts).toBe(2);
  } finally { release(); }
});

test("로그아웃 전 시험 제출의 늦은 인증 오류는 새 화면을 로그인으로 보내지 않는다", async ({ page }) => {
  await mockExam(page);
  await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ json: { success: true, data: null } }));
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let started = false, refreshes = 0, posts = 0;
  page.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/auth/token/refresh") refreshes++; });
  await page.route("**/api/v1/exams/42/submissions", async (route) => {
    started = true; posts++;
    await held;
    return route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "이전 세션 만료" } } });
  });
  try {
    await page.goto("/exams/42/room");
    await page.getByLabel("코드 언어").selectOption("java");
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect.poll(() => started).toBe(true);
    const beforeRefreshes = refreshes;
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === "/api/v1/exams/42/submissions");
    await page.getByRole("button", { name: "로그아웃", exact: true }).click();
    await aborted;
    await expect(page).toHaveURL(/\/login/);
    await page.locator('a[href="/"]').first().click();
    await expect(page).toHaveURL("/");
    release();
    expect(refreshes).toBe(beforeRefreshes); expect(posts).toBe(1);
    await expect(page).toHaveURL("/");
    await expect(page.getByText("이전 세션 만료", { exact: true })).toHaveCount(0);
  } finally { release(); }
});

test("언어를 바꿨다 돌아와도 시험 제출 성공을 표시하고 이후 편집은 미제출로 구분한다", async ({ page }) => {
  const now = await mockExam(page);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let posts = 0;
  await page.route("**/api/v1/exams/42/submissions", async (route) => {
    posts++; await held;
    return route.fulfill({ status: 202, json: { success: true, data: {
      examSubmissionId: "99", status: "RECEIVED", receivedAt: now, draftSeq: "1",
    } } });
  });
  try {
    await page.goto("/exams/42/room");
    await page.getByLabel("코드 언어").selectOption("java");
    const editor = page.getByRole("textbox", { name: "코드 편집기" });
    await expect(editor).toBeVisible(); await editor.pressSequentially("// accepted source");
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect.poll(() => posts).toBe(1);
    await page.getByLabel("코드 언어").selectOption("python3");
    await page.getByLabel("코드 언어").selectOption("java");
    await expect(page.getByText("제출 접수 중…", { exact: true })).toBeVisible();
    release();
    await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
    await editor.pressSequentially("// not submitted");
    await expect(page.getByText("제출 완료", { exact: true })).toHaveCount(0);
  } finally { release(); }
});
