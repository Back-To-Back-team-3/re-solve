import { expect, test } from "@playwright/test";
import { mockExamRoom } from "./exam-fixture";

test("서로 다른 문제·회차 ID의 시험은 Contest Run만 호출하고 공개 결과와 실패를 표시한다", async ({ page }) => {
  await mockExamRoom(page);
  let ordinaryRuns = 0;
  let submissions = 0;
  let currentReads = 0;
  await page.route("**/api/v1/runs", (route) => { ordinaryRuns++; return route.abort(); });
  await page.route("**/api/v1/exams/42/submissions", (route) => { submissions++; return route.abort(); });
  await page.route("**/api/v1/problems/1001", (route) => { currentReads++; return route.abort(); });
  let calls = 0;
  await page.route("**/api/v1/exams/42/problems/1001/runs", (route) => {
    calls++;
    expect(route.request().postDataJSON()).toEqual({ language: "java", sourceCode: "// exam run", customTests: [] });
    return route.fulfill(calls === 1 ? { json: { success: true, data: { results: [
      { type: "SAMPLE", index: 0, output: "3", error: null, timeMs: 12, memoryKb: 1024 },
    ], completedAt: new Date().toISOString() } } } : { status: 409, json: { success: false,
      error: { code: "EXAM_CLOSED", message: "종료된 시험입니다." } } });
  });
  await page.goto("/exams/42/room");
  await page.getByLabel("코드 언어").selectOption("java");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await expect(page.getByText("시험 회차 본문", { exact: true })).toBeVisible();
  await expect(page.locator(".problem-panel table")).toHaveCount(1);
  await expect(page.getByText("예시 해설은 유지합니다.", { exact: true })).toBeVisible();
  await editor.pressSequentially("// exam run");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const result = page.getByLabel("시험 Run 실행 결과");
  await expect(result.getByText("공개 예제 1", { exact: true })).toBeVisible();
  await expect(result.getByText("기대값: 3", { exact: true })).toBeVisible();
  await expect(result.getByText("실제 반환값: 3", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("종료된 시험입니다.", { exact: true })).toBeVisible();
  await expect(result).toHaveCount(0);
  expect(ordinaryRuns).toBe(0); expect(submissions).toBe(0); expect(currentReads).toBe(0);
});
