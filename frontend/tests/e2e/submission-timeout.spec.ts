import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";
import { submissionResponseTimeoutMs } from "@/services/submission-transport";

test("접수 응답 대기 만료 후 원본 코드·키를 유지한 채 같은 제출을 재요청한다", async ({ page }) => {
  await mockPracticePage(page);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  const posts: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/submissions", async (route) => {
    posts.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    if (posts.length === 1) { await held; return route.abort().catch(() => {}); }
    return route.fulfill({ status: 202, json: { success: true, data: { submissionId: "99", status: "QUEUED" } } });
  });
  await page.route("**/api/v1/submissions/99", (route) => route.fulfill({ json: { success: true, data: {
    submissionId: "99", status: "COMPLETED", effectiveResult: { verdict: "AC", passedCount: 1, totalCount: 1 },
  } } }));
  try {
    await page.goto("/problems/1001");
    const editor = page.getByRole("textbox", { name: "코드 편집기" });
    await expect(editor).toBeVisible(); await page.clock.install();
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect.poll(() => posts.length).toBe(1);
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === "/api/v1/submissions");
    await page.clock.fastForward(submissionResponseTimeoutMs);
    await aborted;
    await expect(page.getByText("제출 접수 여부를 확인하지 못했습니다. 다시 시도해 주세요.", { exact: true })).toBeVisible();
    const retry = page.getByRole("button", { name: "같은 제출 재요청", exact: true });
    await expect(retry).toBeEnabled();
    await editor.pressSequentially("// changed after timeout"); await retry.click();
    await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
    expect(posts[0].key).toBeTruthy(); expect(posts[1]).toEqual(posts[0]);
  } finally { release(); }
});
