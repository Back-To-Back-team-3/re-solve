import { expect, test } from "@playwright/test";
import { mockExamRoom } from "./exam-fixture";
import { seedBrowserStorage } from "./storage-fixture";

const languageFailure = { status: 503, json: { success: false,
  error: { code: "JUDGE0_CONNECTION_FAILED", message: "채점 서버에 연결할 수 없습니다." } } };
const javaRuntime = { json: { success: true, data: { content: [
  { language: "java", displayName: "Java", enabled: true, runtimeVersion: "17" },
] } } };

test("언어 조회 503에도 시험 Draft 복원·편집·저장·제출·종료를 유지한다", async ({ page }) => {
  const { now, endsAt } = await mockExamRoom(page);
  await seedBrowserStorage(page, {
    "resolve:editor": JSON.stringify({ language: "java", fontSize: 14 }),
    "re-solve:editor:draft:exam:42:1001": JSON.stringify({ language: "java", code: "// saved draft", seq: "7" }),
  });
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill(languageFailure));
  const drafts: { sourceCode: string; seq: string }[] = [], submissions: unknown[] = [];
  let finishes = 0, runs = 0;
  await page.route("**/api/v1/exams/42/drafts/1001", (route) => {
    const body = route.request().postDataJSON(); drafts.push(body);
    return route.fulfill({ json: { success: true, data: {
      applied: true, serverSeq: body.seq, savedAt: now, personalEndsAt: endsAt, serverNow: now,
    } } });
  });
  await page.route("**/api/v1/exams/42/submissions", (route) => {
    submissions.push(route.request().postDataJSON());
    return route.fulfill({ status: 202, json: { success: true, data: {
      examSubmissionId: "99", status: "RECEIVED", receivedAt: now, draftSeq: "8",
    } } });
  });
  await page.route("**/api/v1/exams/42/problems/1001/runs", (route) => { runs++; return route.abort(); });
  await page.route("**/api/v1/exams/42/finish", (route) => {
    finishes++;
    return route.fulfill({ json: { success: true, data: { status: "FINISHED", finishedAt: now } } });
  });
  await page.goto("/exams/42/room");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await expect(page.locator(".view-lines")).toContainText("// saved draft");
  await expect(page.getByText("시험 회차 본문", { exact: true })).toBeVisible();
  await expect(page.getByText("채점 서버에 연결할 수 없습니다.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "실행", exact: true })).toBeDisabled();
  await editor.press("ControlOrMeta+A"); await editor.pressSequentially("// edited during outage");
  await expect.poll(() => drafts.at(-1)?.sourceCode).toBe("// edited during outage");
  await expect(page.getByRole("button", { name: "제출", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
  expect(submissions).toEqual([{ problemId: "1001", language: "java", sourceCode: "// edited during outage", seq: "8" }]);
  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
  await expect(page).toHaveURL(/\/exams\/42\/result$/);
  expect(finishes).toBe(1); expect(runs).toBe(0);
});

test("언어 조회 대기·재시도 중에도 코드를 유지하고 복구 후 시험 Run을 다시 허용한다", async ({ page }) => {
  await mockExamRoom(page);
  await seedBrowserStorage(page, { "resolve:editor": JSON.stringify({ language: "java", fontSize: 14 }) });
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let releaseRetry = () => {};
  const retryHeld = new Promise<void>((resolve) => { releaseRetry = resolve; });
  let languageReads = 0, problemReads = 0, runs = 0, recovered = false;
  page.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/exams/42/problems") problemReads++; });
  await page.route("**/api/v1/judge/languages", async (route) => {
    languageReads++;
    if (!recovered) { await held; return route.fulfill(languageFailure); }
    await retryHeld;
    return route.fulfill(javaRuntime);
  });
  await page.route("**/api/v1/exams/42/problems/1001/runs", (route) => {
    runs++;
    expect(route.request().postDataJSON()).toEqual({ language: "java", sourceCode: "// keep while loading", customTests: [] });
    return route.fulfill({ json: { success: true, data: { results: [
      { type: "SAMPLE", index: 0, output: "3", error: null, timeMs: 12, memoryKb: 1024 },
    ], completedAt: new Date().toISOString() } } });
  });
  try {
    await page.goto("/exams/42/room");
    const editor = page.getByRole("textbox", { name: "코드 편집기" });
    await expect(editor).toBeVisible();
    await expect(page.getByRole("button", { name: "실행", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "제출", exact: true })).toBeEnabled();
    await editor.pressSequentially("// keep while loading");
    release();
    await expect(page.getByText("채점 서버에 연결할 수 없습니다.", { exact: true })).toBeVisible();
    const previousLanguageReads = languageReads, previousProblemReads = problemReads;
    recovered = true;
    await page.getByRole("button", { name: "실행 언어 다시 조회", exact: true }).click();
    await expect.poll(() => languageReads).toBe(previousLanguageReads + 1);
    await expect(page.getByRole("button", { name: "실행", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "제출", exact: true })).toBeEnabled();
    await expect(page.locator(".view-lines")).toContainText("// keep while loading");
    expect(runs).toBe(0);
    releaseRetry();
    await expect(page.getByRole("button", { name: "실행", exact: true })).toBeEnabled();
    await expect(page.getByRole("button", { name: "실행 언어 다시 조회", exact: true })).toHaveCount(0);
    await expect(page.locator(".view-lines")).toContainText("// keep while loading");
    expect(previousLanguageReads).toBeGreaterThan(0); expect(previousProblemReads).toBeGreaterThan(0);
    expect(languageReads).toBe(previousLanguageReads + 1); expect(problemReads).toBe(previousProblemReads);
    await page.getByRole("button", { name: "실행", exact: true }).click();
    await expect(page.getByLabel("시험 Run 실행 결과").getByText("실제 반환값: 3", { exact: true })).toBeVisible();
    expect(runs).toBe(1);
  } finally { release(); releaseRetry(); }
});
