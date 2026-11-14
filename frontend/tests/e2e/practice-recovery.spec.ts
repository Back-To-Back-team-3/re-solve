import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

const receipt = { submissionId: "2001", judgeAttempt: 1, status: "QUEUED", submittedAt: "2026-11-13T00:00:00+09:00", statusUrl: "/api/v1/submissions/2001" };
const result = { submissionId: "2001", status: "COMPLETED", compileMessage: null,
  effectiveResult: { verdict: "AC", passedCount: 2, totalCount: 2, maxTimeMs: 1, maxMemoryKb: 1024 } };

test("새 접수가 실패해도 이전 제출을 재조회하고 새 요청의 원본으로 재시도한다", async ({ page }) => {
  await mockPracticePage(page);
  let available = false;
  let reads = 0;
  const requests: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/submissions", (route) => {
    requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    if (requests.length === 2) return route.fulfill({ status: 503, json: { success: false,
      error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "새 접수 일시 중단" } } });
    const id = requests.length === 1 ? "2001" : "2002";
    return route.fulfill({ status: 202, json: { success: true, data: { ...receipt, submissionId: id, statusUrl: `/api/v1/submissions/${id}` } } });
  });
  await page.route("**/api/v1/submissions/2001", (route) => {
    reads++;
    return route.fulfill(available ? { json: { success: true, data: result } }
      : { status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "A 조회 일시 중단" } } });
  });
  await page.route("**/api/v1/submissions/2002", (route) => route.fulfill({ json: { success: true,
    data: { ...result, submissionId: "2002", effectiveResult: { ...result.effectiveResult, verdict: "WA", passedCount: 0 } },
  } }));
  await page.goto("/problems/1001");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("A 조회 일시 중단", { exact: true })).toBeVisible();
  await editor.press("ControlOrMeta+A"); await editor.pressSequentially("// B original");
  await page.getByRole("button", { name: "현재 코드로 새 제출", exact: true }).click();
  await expect(page.getByText("새 접수 일시 중단", { exact: true })).toBeVisible();
  await expect(page.getByText("접수된 제출 #2001", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "같은 제출 재요청", exact: true })).toBeEnabled();
  await editor.press("ControlOrMeta+A"); await editor.pressSequentially("// current editor changed");
  const readsBeforeResume = reads;
  available = true;
  await page.getByRole("button", { name: "채점 상태 다시 조회", exact: true }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  expect(reads).toBeGreaterThan(readsBeforeResume);
  expect(requests).toHaveLength(2);
  await page.getByRole("button", { name: "같은 제출 재요청", exact: true }).click();
  await expect(page.getByText("WA 오답", { exact: true })).toBeVisible();
  expect(requests[2]).toEqual(requests[1]);
  expect(requests[2].body).toMatchObject({ sourceCode: "// B original" });
  await expect(page.getByRole("link", { name: "제출 결과 상세", exact: true })).toHaveAttribute("href", "/submissions/2002");
  await page.getByLabel("접수된 제출", { exact: true }).selectOption("2001");
  await expect(page.getByRole("link", { name: "제출 결과 상세", exact: true })).toHaveAttribute("href", "/submissions/2001");
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  expect(requests).toHaveLength(3);
});

test("재시도는 최초 시각을 연장하지 않으며 정확히 24시간이 지나면 안내하고 제한한다", async ({ page }) => {
  await mockPracticePage(page);
  const requests: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/submissions", (route) => {
    requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    return route.abort();
  });
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  const start = new Date("2026-11-13T00:00:00Z");
  await page.clock.install({ time: start });
  await page.clock.pauseAt(new Date(start.getTime() + 1000));
  await page.getByRole("button", { name: "제출", exact: true }).click();
  const retry = page.getByRole("button", { name: "같은 제출 재요청", exact: true });
  await expect(retry).toBeEnabled();
  await page.clock.fastForward(24 * 60 * 60 * 1000 - 1);
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect.poll(() => requests.length).toBe(2);
  await expect(retry).toBeEnabled();
  expect(requests[1]).toEqual(requests[0]);
  await page.clock.fastForward(1);
  await expect(retry).toBeDisabled();
  await expect(page.getByText(/최초 요청 후 24시간이 지나/)).toBeVisible();
  expect(requests).toHaveLength(2);
  await page.getByRole("button", { name: "현재 코드로 새 제출", exact: true }).click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2].key).not.toBe(requests[0].key);
  await expect(retry).toBeEnabled();
});
