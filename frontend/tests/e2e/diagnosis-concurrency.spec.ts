import { expect, test, type Page } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";

async function edit(page: Page, value: string) {
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially(value);
  await expect(page.locator(".view-lines")).toContainText(value);
}

test("저장 경합 뒤 복구 조회에 실패하면 반복 종료 요청도 차단한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// local code");
  state.detail.problems[0].draft = { language: "java", sourceCode: "// other tab", seq: "999", savedAt: state.detail.serverNow };
  state.failDetail = true;
  for (let i = 0; i < 2; i++) {
    await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
    await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
    await expect(page.getByText(/서버의 최신 코드를 확인하지 못했습니다/)).toBeVisible();
    await expect(page.getByRole("button", { name: "최종 제출 및 종료" })).toBeEnabled();
    expect(state.finishes).toBe(0);
  }
  state.failDetail = false;
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  await expect.poll(() => state.finishes).toBe(1);
});

test("언어를 변경하고 새로고침해도 언어별 작성 코드를 복구한다", async ({ page }) => {
  await mockDiagnosis(page);
  const { problemDetail } = await import("./practice-fixture");
  await page.route("**/api/v1/diagnoses/120/problems/1001", (route) => route.fulfill({ json: { success: true, data: problemDetail } }));
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", displayName: "Java", runtimeVersion: "17", enabled: true },
    { language: "python3", displayName: "Python", runtimeVersion: "3", enabled: true },
  ] } } }));
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// java answer");
  await page.getByLabel("코드 언어").selectOption("python3");
  await edit(page, "# python answer");
  await page.getByLabel("코드 언어").selectOption("java");
  await expect(page.locator(".view-lines")).toContainText("// java answer");
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("// java answer");
  await page.getByLabel("코드 언어").selectOption("python3");
  await expect(page.locator(".view-lines")).toContainText("# python answer");
});

test("저장 요청 중 생긴 편집도 이전 저장 성공 후 2초 유휴 조건으로 저장한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => { release = resolve; });
  let waiting = false;
  await page.route("**/api/v1/diagnoses/120/problems/1001/draft", async (route) => {
    if (waiting) return route.fallback();
    waiting = true;
    const body = route.request().postDataJSON();
    await gate;
    state.detail.problems[0].draft = { ...body, savedAt: state.detail.serverNow };
    await route.fulfill({ json: { success: true, data: { applied: true, seq: body.seq, savedAt: state.detail.serverNow } } });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// first save");
  await page.getByRole("button", { name: "지금 저장" }).click();
  await expect.poll(() => waiting).toBe(true);
  await edit(page, "// second edit");
  release();
  await expect.poll(() => state.saves.some((save) => save.body.sourceCode === "// second edit"), { timeout: 4500 }).toBe(true);
});

test("초기 기본 코드도 실제 저장 응답을 받은 뒤에만 서버 저장 완료로 표시한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "지금 저장" }).click();
  await expect.poll(() => state.saves.length).toBeGreaterThan(0);
  expect(state.saves[0].body.sourceCode).toBe("// java starter");
  await expect(page.getByText("서버 저장 완료", { exact: true })).toBeVisible();
});

test("저장 거절 전에 시작한 오래된 폴링으로 종료하지 않고 새 서버 순번을 조회한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.problems[0].draft = { language: "java", sourceCode: "// server four", seq: "4", savedAt: state.detail.serverNow };
  let armed = false;
  let phase = 0;
  let releaseOld: () => void = () => {};
  let releaseFresh: () => void = () => {};
  const oldGate = new Promise<void>((resolve) => { releaseOld = resolve; });
  const freshGate = new Promise<void>((resolve) => { releaseFresh = resolve; });
  await page.route("**/api/v1/diagnoses/120", async (route) => {
    if (!armed || phase >= 2) return route.fallback();
    phase++;
    if (phase === 1) {
      const stale = structuredClone(state.detail);
      await oldGate;
      return route.fulfill({ json: { success: true, data: stale } });
    }
    await freshGate;
    return route.fallback();
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// local five");
  armed = true;
  await page.getByRole("button", { name: "진단 상태 새로고침" }).click();
  await expect.poll(() => phase).toBe(1);
  state.detail.problems[0].draft = { language: "java", sourceCode: "// other tab latest", seq: "999", savedAt: state.detail.serverNow };
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  await expect.poll(() => state.saves.length).toBeGreaterThan(0);
  releaseOld();
  await expect.poll(() => phase).toBe(2);
  expect(state.finishes).toBe(0);
  releaseFresh();
  await expect.poll(() => state.finishes).toBe(1);
  expect(state.detail.problems[0].draft?.sourceCode).toBe("// other tab latest");
});
