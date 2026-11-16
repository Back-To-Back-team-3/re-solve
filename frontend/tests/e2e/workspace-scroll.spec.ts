import { expect, test } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";
import { mockPracticePage, problemDetail } from "./practice-fixture";

test("진단의 긴 문제 설명은 코드 영역을 밀어내지 않고 내부에서 스크롤된다", async ({ page }) => {
  await page.setViewportSize({ width: 1128, height: 852 });
  await mockDiagnosis(page);
  await page.route("**/api/v1/diagnoses/120/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, description: "문제 설명입니다.\n\n".repeat(100),
  } } }));
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  const panel = page.locator(".problem-panel");
  await expect.poll(() => panel.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  const before = await page.locator(".editor-frame").boundingBox();
  const pageScroll = await page.evaluate(() => window.scrollY);
  await panel.hover();
  await page.mouse.wheel(0, 500);
  await expect.poll(() => panel.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.scrollY)).toBe(pageScroll);
  expect((await page.locator(".editor-frame").boundingBox())?.y).toBe(before?.y);
  const panelBottom = (await panel.boundingBox())!;
  const actions = (await page.locator(".editor-actions").boundingBox())!;
  expect(panelBottom.y + panelBottom.height).toBeLessThanOrEqual(actions.y);
});

test("긴 실행 결과는 내부 스크롤로 마지막 출력까지 확인한다", async ({ page }) => {
  await page.setViewportSize({ width: 1128, height: 852 });
  await mockPracticePage(page);
  await page.route("**/api/v1/runs", (route) => route.fulfill({ json: { success: true, data: { compileMessage: null, results: [{
    source: "SAMPLE", testAssetId: "1", verdict: "AC", actualReturnText: "3", expectedReturnText: "3",
    timeMs: 1, memoryKb: 1024, stdout: "debug line\n".repeat(100) + "debug end",
  }] } } }));
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const results = page.locator("section.results");
  await expect(results).toContainText("debug end");
  await expect.poll(() => results.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await results.evaluate((element) => element.scrollIntoView({ block: "center" }));
  const pageScroll = await page.evaluate(() => window.scrollY);
  await results.hover();
  await page.mouse.wheel(0, 100000);
  await expect.poll(() => results.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)).toBeLessThanOrEqual(1);
  expect(await page.evaluate(() => window.scrollY)).toBe(pageScroll);
});
