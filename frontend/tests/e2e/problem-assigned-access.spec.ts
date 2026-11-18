import { expect, test, type Page } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";
import { mockApi } from "./api-fixture";
import type { AssignmentDetail } from "@/services/types";

const assignedPath = "/api/v1/studies/me/assigned-problems/1001";

async function mockLockedProblem(page: Page) {
  await mockPracticePage(page);
  await page.route("**/api/v1/members/me/tag-levels", (route) =>
    route.fulfill({ json: { success: true, data: { content: [] } } }));
}

for (const fromAssignment of [false, true]) {
  test(`배정 문제는 미진입 태그도 ${fromAssignment ? "문제집 링크" : "직접 URL"}에서 풀이를 허용한다`, async ({ page }) => {
    await mockLockedProblem(page);
    let checks = 0, entries = 0;
    page.on("request", (request) => {
      if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/members/me/tag-levels") entries++;
    });
    await page.route(`**${assignedPath}`, (route) => {
      checks++;
      return route.fulfill({ json: { success: true, data: { problemId: "1001", assigned: true } } });
    });
    if (fromAssignment) {
      const assignment: AssignmentDetail = {
        assignmentId: "9", title: "배정 문제집", goal: "함께 풀기", tagId: "1", status: "OPEN",
        isPreSolvedAllowed: false, startsAt: "2026-11-18T00:00:00+09:00", deadlineAt: null,
        problems: [{ problemId: "1001", assignedRevisionId: "2001", revisionNumber: 1,
          title: problemDetail.title, difficulty: 1, tags: ["배열"], status: "ACTIVE", myCompletionType: null }],
        memberProgress: [], studyStats: { avgCompletionRate: 0, avgOnTimeRate: 0 },
      };
      await page.route("**/api/v1/studies/4/assignments/9", (route) =>
        route.fulfill({ json: { success: true, data: assignment } }));
      await page.goto("/studies/4/assignments/9");
      await page.getByRole("link", { name: problemDetail.title, exact: true }).click();
    } else await page.goto("/problems/1001");
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toHaveCount(0);
    expect(checks).toBeGreaterThan(0); expect(entries).toBe(0);

    // 배정 예외는 화면 진입만 허용하며 기존 PRACTICE 요청에 문제집 ID를 덧붙이지 않는다.
    await page.route("**/api/v1/runs", (route) => route.fulfill({ status: 503, json: {
      success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "실행 서버 오류" },
    } }));
    const run = page.waitForRequest("**/api/v1/runs");
    await page.getByRole("button", { name: "실행", exact: true }).click();
    expect((await run).postDataJSON()).toEqual({ problemId: "1001", problemRevisionId: "2001",
      language: "java", sourceCode: "// java starter", useSamples: true, customInputs: [] });
    await expect(page.getByText("실행 서버 오류", { exact: true })).toBeVisible();
    await page.route("**/api/v1/submissions", (route) => route.fulfill({ status: 503, json: {
      success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "접수 서버 오류" },
    } }));
    const submission = page.waitForRequest("**/api/v1/submissions");
    await page.getByRole("button", { name: "제출", exact: true }).click();
    expect((await submission).postDataJSON()).toEqual({ problemId: "1001", problemRevisionId: "2001",
      language: "java", sourceCode: "// java starter" });
    await expect(page.getByText("접수 서버 오류", { exact: true })).toBeVisible();
    expect(entries).toBe(0);
  });
}

for (const response of ["unassigned", "unavailable", "wrong-problem"] as const) {
  test(`배정 조회 ${response}이면 잠금을 유지하고 재확인으로 복구한다`, async ({ page }) => {
    await mockLockedProblem(page);
    let checks = 0, allow = false;
    await page.route(`**${assignedPath}`, (route) => {
      checks++;
      if (!allow && response === "unavailable") return route.fulfill({ status: 503, json: {
        success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "스터디 조회 실패" },
      } });
      return route.fulfill({ json: { success: true, data: {
        problemId: !allow && response === "wrong-problem" ? "1002" : "1001",
        assigned: allow || response === "wrong-problem",
      } } });
    });
    await page.goto("/problems/1001");
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
    await expect(page.getByRole("button", { name: "실행", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "제출", exact: true })).toHaveCount(0);
    const beforeRetry = checks; allow = true;
    await page.getByRole("button", { name: "레벨 다시 확인", exact: true }).click();
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
    expect(checks).toBeGreaterThan(beforeRetry);
  });
}

test("개인 레벨이 충분하면 배정 여부를 조회하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  let checks = 0;
  page.on("request", (request) => { if (new URL(request.url()).pathname === assignedPath) checks++; });
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  expect(checks).toBe(0);
});

test("레벨 조회가 실패하면 배정 예외를 조회하거나 풀이 화면을 열지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  let checks = 0;
  page.on("request", (request) => { if (new URL(request.url()).pathname === assignedPath) checks++; });
  await page.route("**/api/v1/members/me/tag-levels", (route) => route.fulfill({ status: 503, json: {
    success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "레벨 조회 실패" },
  } }));
  await page.goto("/problems/1001");
  await expect(page.getByText("레벨을 확인하지 못했습니다.", { exact: false })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  expect(checks).toBe(0);
});

test("이전 문제의 늦은 배정 응답으로 다른 문제의 잠금을 풀지 않는다", async ({ page }) => {
  await mockApi(page);
  await page.route("**/api/v1/members/me/tag-levels", (route) =>
    route.fulfill({ json: { success: true, data: { content: [] } } }));
  let release = () => {}, started = false;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/studies/me/assigned-problems/*", async (route) => {
    const problemId = new URL(route.request().url()).pathname.split("/").at(-1);
    if (problemId === "1001") { started = true; await held; }
    return route.fulfill({ json: { success: true, data: { problemId, assigned: problemId === "1001" } } });
  });
  try {
    await page.goto("/problems/1001");
    await expect.poll(() => started).toBe(true);
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
    await page.getByRole("link", { name: "← 문제 목록", exact: true }).click();
    await page.getByRole("link", { name: "고정 회차 배열 최댓값", exact: true }).click();
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
    const previousResponse = page.waitForResponse(`**${assignedPath}`);
    release(); await (await previousResponse).finished();
    await expect(page.getByRole("heading", { name: "고정 회차 배열 최댓값", exact: true })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
  } finally { release(); }
});
