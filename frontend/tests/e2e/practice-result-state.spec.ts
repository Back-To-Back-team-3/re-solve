import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

test("초기 안내는 실행·접수 실패 뒤 숨기고 이전 성공 결과는 명시적으로 선택한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/runs", (route) => route.fulfill({ status: 503,
    json: { success: false, error: { code: "RUN_TIMEOUT", message: "실행 실패" } } }));
  let attempts = 0;
  await page.route("**/api/v1/submissions", (route) => {
    attempts++;
    return route.fulfill(attempts === 2 ? { status: 503, json: { success: false,
      error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "접수 실패" } } }
      : { status: 202, json: { success: true, data: { submissionId: String(attempts), status: "QUEUED" } } });
  });
  await page.route(/\/api\/v1\/submissions\/\d+$/, (route) => route.fulfill({ json: { success: true, data: {
    submissionId: route.request().url().split("/").at(-1), status: "COMPLETED", compileMessage: null,
    effectiveResult: { verdict: "AC", passedCount: 1, totalCount: 1 },
  } } }));
  await page.goto("/problems/1001");
  await expect(page.getByText("코드를 실행해 보세요", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("실행 실패", { exact: true })).toBeVisible();
  await expect(page.getByText("코드를 실행해 보세요", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("접수 실패", { exact: true })).toBeVisible();
  await expect(page.getByText("AC 정답", { exact: true })).toHaveCount(0);
  await expect(page.getByText("코드를 실행해 보세요", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "이전 결과 보기", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("실행 실패", { exact: true })).toBeVisible();
  await expect(page.getByText("접수 실패", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "같은 제출 재요청", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  await page.getByLabel("접수된 제출", { exact: true }).selectOption("1");
  await expect(page.getByRole("link", { name: "제출 결과 상세" })).toHaveAttribute("href", "/submissions/1");
});

test("접수 실패 후 언어를 바꿨다 돌아와도 실패 안내·원본 재시도를 유지한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", displayName: "Java", enabled: true, runtimeVersion: "17" },
    { language: "python3", displayName: "Python", enabled: true, runtimeVersion: "3" },
  ] } } }));
  const posts: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/submissions", (route) => {
    posts.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    return route.fulfill(posts.length === 2 ? { status: 503, json: { success: false,
      error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "B 접수 실패" } } }
      : { status: 202, json: { success: true, data: { submissionId: String(posts.length), status: "QUEUED" } } });
  });
  await page.route(/\/api\/v1\/submissions\/\d+$/, (route) => route.fulfill({ json: { success: true, data: {
    submissionId: route.request().url().split("/").at(-1), status: "COMPLETED",
    effectiveResult: { verdict: "AC", passedCount: 1, totalCount: 1 },
  } } }));
  await page.goto("/problems/1001");
  await page.getByLabel("코드 언어").selectOption("java");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  await editor.press("ControlOrMeta+A"); await editor.pressSequentially("// original B");
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("B 접수 실패", { exact: true })).toBeVisible();
  await page.getByLabel("코드 언어").selectOption("python3");
  await expect(page.getByRole("button", { name: "같은 제출 재요청", exact: true })).toHaveCount(0);
  await page.getByLabel("코드 언어").selectOption("java");
  await expect(page.getByText("B 접수 실패", { exact: true })).toBeVisible();
  await expect(page.getByText("AC 정답", { exact: true })).toHaveCount(0);
  await expect(page.getByText("코드를 실행해 보세요", { exact: true })).toHaveCount(0);
  await editor.press("ControlOrMeta+A"); await editor.pressSequentially("// changed after restore");
  await page.getByRole("button", { name: "같은 제출 재요청", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  expect(posts[1].key).toBeTruthy();
  expect(posts[2]).toEqual(posts[1]);
  expect(posts[2].body).toMatchObject({ sourceCode: "// original B" });
  await expect(page.getByText("B 접수 실패", { exact: true })).toHaveCount(0);
  await page.getByLabel("접수된 제출", { exact: true }).selectOption("1");
  await page.getByLabel("코드 언어").selectOption("python3");
  await page.getByLabel("코드 언어").selectOption("java");
  await expect(page.getByRole("link", { name: "제출 결과 상세" })).toHaveAttribute("href", "/submissions/1");
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
});
