import { expect, test, type Page } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";
import { readBrowserStorage, seedBrowserStorage } from "./storage-fixture";

/** 시험 편집기의 기본 응시 상태와 로컬 Draft를 준비한다. */
async function mockExamRoom(page: Page) {
  await mockPracticePage(page);
  await seedBrowserStorage(page, {
    "resolve:editor": JSON.stringify({ language: "java", fontSize: 14 }),
    "re-solve:editor:draft:exam:42:1001": JSON.stringify({ language: "java", code: "// exam saved", seq: "7" }),
  });
  const now = new Date().toISOString();
  const endsAt = new Date(Date.now() + 3600000).toISOString();
  await page.route("**/api/v1/exams/42/problems", (route) => route.fulfill({ json: { success: true, data: {
    problems: [{ problemId: "1001", title: "시험 문제", body: "시험 본문", score: "100", languages: ["java"] }],
    serverNow: now, personalEndsAt: endsAt,
  } } }));
  return { now, endsAt };
}

test("시험 에디터의 Draft 복원·자동 저장·제출은 일반 풀이 경로와 분리한다", async ({ page }) => {
  const { now, endsAt } = await mockExamRoom(page);
  const drafts: unknown[] = [];
  const submissions: unknown[] = [];
  let practicePosts = 0;
  await page.route("**/api/v1/submissions", (route) => { practicePosts++; return route.abort(); });
  await page.route("**/api/v1/exams/42/drafts/1001", (route) => {
    expect(route.request().method()).toBe("PUT");
    drafts.push(route.request().postDataJSON());
    return route.fulfill({ json: { success: true, data: {
      applied: true, serverSeq: "7", savedAt: now, personalEndsAt: endsAt, serverNow: now,
    } } });
  });
  await page.route("**/api/v1/exams/42/submissions", (route) => {
    submissions.push(route.request().postDataJSON());
    return route.fulfill({ status: 202, json: { success: true, data: {
      examSubmissionId: "99", status: "RECEIVED", receivedAt: now, draftSeq: "8",
    } } });
  });
  await page.goto("/exams/42/room");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await expect(page.locator(".view-lines")).toContainText("// exam saved");
  await expect(page.getByText("실행 입력 설정", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "직접 입력 추가", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "실행", exact: true })).toBeEnabled();
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// exam edited");
  await expect.poll(() => drafts.length).toBeGreaterThan(0);
  expect(drafts.at(-1)).toEqual({ language: "java", sourceCode: "// exam edited", seq: "7" });
  await expect.poll(async () => JSON.parse(await readBrowserStorage(page, "re-solve:editor:draft:exam:42:1001") ?? "{}").seq).toBe("8");
  const submit = page.getByRole("button", { name: "제출", exact: true });
  await expect(submit).toBeEnabled();
  await submit.press("Enter");
  await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
  expect(submissions).toEqual([{ problemId: "1001", language: "java", sourceCode: "// exam edited", seq: "8" }]);
  expect(practicePosts).toBe(0);
});

test("시험 Run은 시험과 문제 ID로 공개 예제를 실행하고 제출을 만들지 않는다", async ({ page }) => {
  const { now } = await mockExamRoom(page);
  const runs: unknown[] = [];
  let unexpectedPosts = 0;
  await page.route(/\/api\/v1\/(?:runs|submissions|exams\/42\/submissions)$/, (route) => {
    unexpectedPosts++;
    return route.abort();
  });
  // 고정 회차 조회는 서버 책임이므로 시험 Run에는 문제 ID만 전달한다.
  await page.route("**/api/v1/exams/42/problems/1001/runs", (route) => {
    expect(route.request().method()).toBe("POST");
    runs.push(route.request().postDataJSON());
    return route.fulfill({ json: { success: true, data: { completedAt: now, results: [
      { type: "SAMPLE", index: 0, output: "3", error: null, timeMs: 2, memoryKb: 1024 },
      { type: "SAMPLE", index: 1, output: null, error: "실행 시간 초과", timeMs: 1000, memoryKb: 1024 },
    ] } } });
  });
  await page.goto("/exams/42/room");
  await expect(page.locator(".view-lines")).toContainText("// exam saved");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("실제 반환값: 3", { exact: true })).toBeVisible();
  await expect(page.getByText("실행 시간 초과", { exact: true })).toBeVisible();
  expect(runs).toEqual([{ language: "java", sourceCode: "// exam saved" }]);
  expect(unexpectedPosts).toBe(0);
});
