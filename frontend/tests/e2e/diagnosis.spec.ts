import { expect, test, type Page } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";
import { readBrowserStorage, seedBrowserStorage } from "./storage-fixture";

async function edit(page: Page, value: string) {
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially(value);
  await expect(page.locator(".view-lines")).toContainText(value);
}

test("홈에서 진단을 시작해 문항별 저장·제출·종료·결과 조회까지 연결한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.goto("/");
  await page.getByRole("link", { name: "내 실력 진단" }).click();
  await page.getByRole("button", { name: "진단 시작", exact: true }).first().click();
  await expect(page).toHaveURL(/\/diagnoses\/120$/);
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// answer one");
  await expect.poll(() => state.saves.some((save) => save.body.sourceCode === "// answer one")).toBe(true);
  await page.getByRole("button", { name: "문항 제출", exact: true }).click();
  await expect(page.getByText(/제출 #880 접수 완료/)).toBeVisible();
  expect(state.posts[0].sourceCode).toBe("// answer one");
  await page.getByRole("button", { name: /문제 2 ·/ }).click();
  await expect(page.getByRole("heading", { name: "진단 문제 1002", exact: true })).toBeVisible();
  await edit(page, "// answer two");
  await page.getByRole("button", { name: "최종 제출 및 종료", exact: true }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료", exact: true }).click();
  await expect(page.getByRole("heading", { name: "채점 결과를 기다리고 있어요" })).toBeVisible();
  expect(state.saves.some((save) => save.id === "1002" && save.body.sourceCode === "// answer two")).toBe(true);
  expect(state.finishes).toBe(1);
  state.detail = { ...state.detail, status: "SCORED", result: { score: 3, resultLevel: "LV2", appliedLevel: "LV2" } };
  await page.getByRole("button", { name: "진단 상태 새로고침" }).click();
  await expect(page.getByText("적용 레벨: LV2", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
});

test("더 큰 로컬 순번을 복구하고 다른 회원·회차의 Draft는 사용하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.problems[0].draft = { language: "java", sourceCode: "// server", seq: "4", savedAt: state.detail.serverNow };
  await seedBrowserStorage(page, {
    "re-solve:diagnosis:7:120:1001": JSON.stringify({ revisionId: "2001", draft: { language: "java", sourceCode: "// local newer", seq: "5" } }),
    "re-solve:diagnosis:8:120:1001": JSON.stringify({ revisionId: "2001", draft: { language: "java", sourceCode: "// another user", seq: "9" } }),
    "re-solve:diagnosis:7:120:1002": JSON.stringify({ revisionId: "wrong", draft: { language: "java", sourceCode: "// wrong revision", seq: "10" } }),
  });
  await page.goto("/diagnoses/120");
  await expect(page.locator(".view-lines")).toContainText("// local newer");
  await page.getByRole("button", { name: "지금 저장" }).click();
  await expect.poll(() => state.saves.some((save) => save.body.seq === "5")).toBe(true);
  await page.getByRole("button", { name: /문제 2 ·/ }).click();
  await expect(page.locator(".view-lines")).toContainText("// java starter");
  await expect(page.locator(".view-lines")).not.toContainText("wrong revision");
});

test("제출 응답 유실 후 편집·새로고침해도 최초 제출 원본으로 재요청한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.failSubmit = true;
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// original submission");
  await page.getByRole("button", { name: "문항 제출", exact: true }).click();
  await expect(page.getByRole("button", { name: "같은 제출 재요청" })).toBeEnabled();
  const original = state.posts[0];
  await edit(page, "// edited after failure");
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("// edited after failure");
  state.failSubmit = false;
  await page.getByRole("button", { name: "같은 제출 재요청" }).click();
  await expect(page.getByText(/제출 #880 접수 완료/)).toBeVisible();
  expect(state.posts[1]).toEqual(original);
  await expect(page.locator(".view-lines")).toContainText("// edited after failure");
});

test("저장 실패 시 진단을 종료하지 않고 원본을 유지해 다시 종료한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.failSave = true;
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// must save");
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  await expect(page.getByText("COMMON_DEPENDENCY_UNAVAILABLE", { exact: true })).toBeVisible();
  expect(state.finishes).toBe(0);
  state.failSave = false;
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  await expect.poll(() => state.finishes).toBe(1);
});

test("제한 시간은 서버 시각으로 계산하고 만료 후 제출하지 않고 종료 상태를 조회한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.endsAt = new Date(Date.parse(state.detail.serverNow) + 3000).toISOString();
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.clock.install();
  await page.clock.fastForward(4000);
  await expect(page.getByText(/제한 시간이 끝났습니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "문항 제출", exact: true })).toBeDisabled();
  expect(state.finishes).toBe(0);
  state.detail.status = "CLOSED";
  await page.getByRole("button", { name: "진단 상태 새로고침" }).click();
  await expect(page.getByRole("heading", { name: "채점 결과를 기다리고 있어요" })).toBeVisible();
  expect(state.posts).toHaveLength(0);
});

test("응시 중 판정은 숨기고 종료 후 실패 및 확정된 점수만 표시한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.result = { score: 6, resultLevel: "LV3", appliedLevel: "LV3" };
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await expect(page.getByText("진단 결과 레벨: LV3")).toHaveCount(0);
  state.detail = { ...state.detail, status: "SCORED", problems: state.detail.problems.map((problem) => ({ ...problem,
    lastSubmission: { submissionStatus: "FAILED", draftSeq: "1" } })), result: { score: 0, resultLevel: "LV1", appliedLevel: "LV2" } };
  await page.getByRole("button", { name: "진단 상태 새로고침" }).click();
  await expect(page.getByText("진단 결과 레벨: LV1", { exact: true })).toBeVisible();
  await expect(page.getByText("적용 레벨: LV2", { exact: true })).toBeVisible();
  await expect(page.getByText(/채점 장애가 발생했습니다/)).toHaveCount(3);
});

test("다른 회원의 진단은 풀이·제출 화면을 표시하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.forbidden = true;
  await page.goto("/diagnoses/120");
  await expect(page.getByText("DIAGNOSIS_NOT_FOUND", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});

test("시작 응답이 유실되면 다시 시작하지 않고 진행 중 진단을 복구한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.failStart = true;
  let starts = 0;
  page.on("request", (request) => { if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/diagnoses") starts++; });
  await page.goto("/diagnoses");
  await page.getByRole("button", { name: "진단 시작", exact: true }).first().click();
  await expect(page).toHaveURL(/\/diagnoses\/120$/);
  expect(starts).toBe(1);
});

test("이전 저장 응답을 기다리는 동안 편집한 코드도 종료 전에 저장한다", async ({ page }) => {
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
  await edit(page, "// latest code");
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  expect(state.finishes).toBe(0);
  release();
  await expect(page.getByRole("heading", { name: "채점 결과를 기다리고 있어요" })).toBeVisible();
  expect(state.saves.some((save) => save.body.sourceCode === "// latest code")).toBe(true);
});

test("연속 편집 중에도 10초 이내 서버에 저장하고 상태 조회 실패 시 편집을 유지한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  const code = "// continuous typing ".repeat(6);
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially(code, { delay: 100 });
  expect(state.saves.length).toBeGreaterThan(0);
  expect(state.saves[0].body.sourceCode.length).toBeLessThan(code.length);
  state.failDetail = true;
  await page.getByRole("button", { name: "진단 상태 새로고침" }).click();
  await expect(page.getByText("COMMON_DEPENDENCY_UNAVAILABLE", { exact: true })).toBeVisible();
  const raw = await readBrowserStorage(page, "re-solve:diagnosis:7:120:1001");
  expect(raw).not.toBeNull();
  expect(JSON.parse(raw!).draft.sourceCode).toBe(code);
});

test("이미 진행 중인 진단은 새 접수 없이 이어서 푼다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.started = true;
  let posts = 0;
  page.on("request", (request) => { if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/diagnoses") posts++; });
  await page.goto("/diagnoses");
  await page.getByRole("link", { name: "이어서 풀기", exact: true }).click();
  await expect(page).toHaveURL(/\/diagnoses\/120$/);
  expect(posts).toBe(0);
});

test("진단 비활성·출제 후보 부족 오류를 성공으로 표시하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.errorCode = "DIAGNOSIS_PROBLEMS_UNAVAILABLE";
  await page.goto("/diagnoses");
  await page.getByRole("button", { name: "진단 시작", exact: true }).first().click();
  await expect(page.getByText(/출제 가능한 문제가 부족합니다/)).toBeVisible();
  await expect(page).toHaveURL(/\/diagnoses$/);
  expect(state.started).toBe(false);
  state.errorCode = "COMMON_DEPENDENCY_UNAVAILABLE";
  await page.getByRole("button", { name: "진단 시작", exact: true }).first().click();
  await expect(page.getByText("COMMON_DEPENDENCY_UNAVAILABLE", { exact: true })).toBeVisible();
  expect(state.started).toBe(false);
});

test("종료 응답이 유실돼도 서버 종료 상태를 복구하며 새 종료를 반복하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.route("**/api/v1/diagnoses/120/finish", async (route) => {
    state.finishes++;
    state.detail.status = "CLOSED";
    await route.abort();
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "최종 제출 및 종료" }).click();
  await page.getByRole("button", { name: "저장 후 진단 종료" }).click();
  await expect(page.getByRole("heading", { name: "채점 결과를 기다리고 있어요" })).toBeVisible();
  expect(state.finishes).toBe(1);
  await expect(page.getByRole("button", { name: "최종 제출 및 종료" })).toHaveCount(0);
});

test("진단 본문 이미지는 회원용 진단 경로로만 조회하고 새로고침 후에도 표시한다", async ({ page }) => {
  await mockDiagnosis(page);
  let reads = 0;
  let unsafeReads = 0;
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path.includes("/images/") && !path.startsWith("/api/v1/diagnoses/")) unsafeReads++;
  });
  const { problemDetail } = await import("./practice-fixture");
  await page.route("**/api/v1/diagnoses/120/problems/1001", (route) => route.fulfill({ json: { success: true,
    data: { ...problemDetail, description: "진단 본문 ![진단 도식](image:701)" } } }));
  await page.route("**/api/v1/diagnoses/120/problems/1001/images/701", (route) => {
    reads++;
    expect(route.request().headers().authorization).toBe("Bearer test-access");
    return route.fulfill({ contentType: "image/png", body: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=", "base64") });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("img", { name: "진단 도식" })).toBeVisible();
  const beforeReload = reads;
  await page.reload();
  await expect.poll(() => page.getByRole("img", { name: "진단 도식" }).evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(1);
  expect(reads).toBeGreaterThan(beforeReload);
  expect(unsafeReads).toBe(0);
});

test("진단 이미지가 실패해도 원래 오류를 안내하고 작성 중인 코드를 유지한다", async ({ page }) => {
  await mockDiagnosis(page);
  const { problemDetail } = await import("./practice-fixture");
  await page.route("**/api/v1/diagnoses/120/problems/1001", (route) => route.fulfill({ json: { success: true,
    data: { ...problemDetail, description: "![조회 실패 도식](image:701)" } } }));
  await page.route("**/api/v1/diagnoses/120/problems/1001/images/701", (route) => route.fulfill({ status: 503,
    json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "이미지 조회 장애" } } }));
  await page.goto("/diagnoses/120");
  await expect(page.getByText("조회 실패 도식: 이미지 조회 장애", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await edit(page, "// code survives image error");
});


test("진단 결과로 적용된 레벨을 홈에서도 실제 회원 응답으로 표시한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.started = true;
  state.detail = { ...state.detail, status: "SCORED", result: { score: 3, resultLevel: "LV2", appliedLevel: "LV2" } };
  await page.goto("/");
  await expect(page.getByText("Lv.2", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "내 실력 진단" }).click();
  await expect(page).toHaveURL(/\/diagnoses/);
});
