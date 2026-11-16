import { expect, test } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";

test("5초 자동 동기화로 종료를 반영하고 수동 새로고침 영역은 표시하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.clock.install();
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await expect(page.getByRole("button", { name: "진단 상태 새로고침" })).toHaveCount(0);
  state.detail.status = "CLOSED";
  await page.clock.fastForward(5000);
  await expect(page.getByRole("heading", { name: "채점 결과를 기다리고 있어요" })).toBeVisible();
});

test("동기화 실패 토스트는 자동으로 닫히고 연속 실패에는 반복되지 않으며 재시도할 수 있다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.clock.install();
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  state.failDetail = true;
  await page.clock.fastForward(5000);
  const alert = page.getByRole("alert").filter({ hasText: "진단 상태를 동기화하지 못했습니다" });
  await expect(alert).toBeVisible();
  await page.clock.fastForward(5000);
  await expect(alert).toHaveCount(0);
  await page.clock.fastForward(5000);
  await expect(alert).toHaveCount(0);
  state.failDetail = false;
  const recovered = page.waitForResponse((response) => response.url().endsWith("/api/v1/diagnoses/120") && response.status() === 200);
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await recovered;
  state.failDetail = true;
  await page.clock.fastForward(5000);
  await expect(alert).toBeVisible();
  state.failDetail = false;
  await alert.getByRole("button", { name: "재시도", exact: true }).click();
  await expect(alert).toHaveCount(0);
});

test("창 복귀 동기화는 편집 중인 코드보다 오래된 서버 Draft로 덮어쓰지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.goto("/diagnoses/120");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// my current answer");
  state.detail.problems[0].draft = { language: "java", sourceCode: "// old server answer", seq: "1", savedAt: state.detail.serverNow };
  const response = page.waitForResponse((response) => response.url().endsWith("/api/v1/diagnoses/120"));
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await response;
  await expect(page.locator(".view-lines")).toContainText("// my current answer");
});
