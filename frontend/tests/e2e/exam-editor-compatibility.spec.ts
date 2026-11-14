import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";
import { readBrowserStorage, seedBrowserStorage } from "./storage-fixture";

test("시험 에디터의 Draft 복원·자동 저장·제출은 일반 풀이 경로와 분리한다", async ({ page }) => {
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
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
  expect(submissions).toEqual([{ problemId: "1001", language: "java", sourceCode: "// exam edited", seq: "8" }]);
  expect(practicePosts).toBe(0);
});
