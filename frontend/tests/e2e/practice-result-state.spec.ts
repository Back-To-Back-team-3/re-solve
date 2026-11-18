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
  await page.getByRole("button", { name: "같은 제출 재요청", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  await page.getByLabel("접수된 제출", { exact: true }).selectOption("1");
  await expect(page.getByRole("link", { name: "제출 결과 상세" })).toHaveAttribute("href", "/submissions/1");
});
