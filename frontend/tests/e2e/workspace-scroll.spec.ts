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
  expect(Math.abs(panelBottom.y + panelBottom.height - actions.y)).toBeLessThanOrEqual(2);
  const editorBox = (await page.locator(".editor-frame").boundingBox())!;
  expect(Math.abs(editorBox.y + editorBox.height - actions.y)).toBeLessThanOrEqual(2);
  await expect(page.getByText("진단 중에는 판정·힌트를 제공하지 않습니다.", { exact: false })).toHaveCount(0);
  for (const height of [720, 1000]) {
    await page.setViewportSize({ width: 1128, height });
    await expect.poll(async () => {
      const box = (await panel.boundingBox())!;
      const bar = (await page.locator(".editor-actions").boundingBox())!;
      return Math.abs(box.y + box.height - bar.y);
    }).toBeLessThanOrEqual(2);
  }
});

test("긴 실행 결과는 내부 스크롤로 마지막 출력까지 확인한다", async ({ page }) => {
  await page.setViewportSize({ width: 1128, height: 852 });
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, description: "일반 문제 설명입니다.\n\n".repeat(100),
  } } }));
  await page.route("**/api/v1/runs", (route) => route.fulfill({ json: { success: true, data: { compileMessage: null, results: [{
    source: "SAMPLE", testAssetId: "1", verdict: "AC", actualReturnText: "3", expectedReturnText: "3",
    timeMs: 1, memoryKb: 1024, stdout: "debug line\n".repeat(100) + "debug end",
  }] } } }));
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const panel = page.locator(".problem-panel");
  await expect.poll(() => panel.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await panel.hover();
  await page.mouse.wheel(0, 500);
  await expect.poll(() => panel.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
  const results = page.locator("section.results");
  await expect(results).toContainText("debug end");
  await expect.poll(() => results.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await results.evaluate((element) => element.scrollIntoView({ block: "center" }));
  const pageScroll = await page.evaluate(() => window.scrollY);
  await results.hover();
  await page.mouse.wheel(0, 100000);
  await expect.poll(() => results.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)).toBeLessThanOrEqual(1);
  expect(await page.evaluate(() => window.scrollY)).toBe(pageScroll);
  const box = (await results.boundingBox())!;
  const bar = (await page.locator(".editor-actions").boundingBox())!;
  expect(Math.abs(box.y + box.height - bar.y)).toBeLessThanOrEqual(2);
});
