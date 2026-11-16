import { expect, test } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";
import { problemDetail } from "./practice-fixture";

const response = { completedAt: "2026-11-16T00:00:00Z", results: [
  { type: "SAMPLE", index: 0, output: "3", stdout: "diagnosis debug", error: null, timeMs: 2, memoryKb: 1024 },
] };

test("진단 Run은 Member 경로로 실행하며 Draft·제출·종료를 만들지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.problems[0].draft = { language: "java", sourceCode: "// java starter", seq: "1", savedAt: state.detail.serverNow };
  let body: unknown;
  await page.route("**/api/v1/diagnoses/120/problems/1001/runs", async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({ json: { success: true, data: response } });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const result = page.getByRole("region", { name: "진단 Run 실행 결과" });
  await expect(result).toContainText("실제 반환값: 3");
  await expect(result).toContainText("diagnosis debug");
  await expect(result).toContainText("실행 시간: 2ms · 메모리: 1024KiB");
  expect(body).toEqual({ language: "java", sourceCode: "// java starter", customTests: [] });
  expect(state.posts).toHaveLength(0);
  expect(state.saves).toHaveLength(0);
  expect(state.finishes).toBe(0);
});

test("복원 언어의 런타임이 없으면 Run을 막고 저장과 사용 가능한 언어 전환은 유지한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.detail.problems[0].draft = { language: "cpp", sourceCode: "// restored cpp", seq: "1", savedAt: state.detail.serverNow };
  const requests: string[] = [];
  await page.route("**/api/v1/diagnoses/120/problems/1001/runs", async (route) => {
    requests.push(route.request().postDataJSON().language);
    await route.fulfill({ json: { success: true, data: response } });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "코드 언어" })).toHaveValue("cpp");
  await expect(page.getByRole("button", { name: "실행", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "문항 제출", exact: true })).toBeDisabled();
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// edited cpp");
  await page.getByRole("button", { name: "지금 저장", exact: true }).click();
  await expect.poll(() => state.saves.some((save) => save.body.language === "cpp" && save.body.sourceCode === "// edited cpp")).toBe(true);
  expect(requests).toEqual([]);
  await page.getByRole("combobox", { name: "코드 언어" }).selectOption("java");
  await expect(page.getByRole("button", { name: "실행", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByRole("region", { name: "진단 Run 실행 결과" })).toContainText("실제 반환값: 3");
  expect(requests).toEqual(["java"]);
});

test("진단 직접 입력은 최대 10개이며 INT64 JSON 숫자의 원문을 보존한다", async ({ page }) => {
  await mockDiagnosis(page);
  await page.route("**/api/v1/diagnoses/120/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, functionSpec: { name: "solution", parameters: [{ name: "items", type: { kind: "INT64" } }], returnType: { kind: "INT64" } },
  } } }));
  let raw = "";
  await page.route("**/api/v1/diagnoses/120/problems/1001/runs", async (route) => {
    raw = route.request().postData() ?? "";
    await route.fulfill({ json: { success: true, data: response } });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await expect(page.getByLabel("공개 예제 실행")).toBeChecked();
  await expect(page.getByLabel("공개 예제 실행")).toBeDisabled();
  for (let index = 1; index <= 10; index++) {
    await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
    await page.getByLabel(`직접 입력 ${index} items`, { exact: true }).fill(index === 1 ? "9223372036854775807" : String(index));
  }
  await expect(page.getByRole("button", { name: "직접 입력 추가", exact: true })).toBeDisabled();
  await expect(page.getByText("기대 출력", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect.poll(() => raw).toContain('"args":[9223372036854775807]');
  expect(JSON.parse(raw).customTests).toHaveLength(10);
});

test("이전 문항 Run의 늦은 결과는 전환한 문항에 표시하지 않는다", async ({ page }) => {
  await mockDiagnosis(page);
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => { release = resolve; });
  let received = false;
  await page.route("**/api/v1/diagnoses/120/problems/1001/runs", async (route) => {
    received = true;
    await gate;
    await route.fulfill({ json: { success: true, data: response } });
  });
  await page.goto("/diagnoses/120");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect.poll(() => received).toBe(true);
  await page.getByRole("button", { name: /문제 2 · Lv.2/ }).click();
  release();
  await expect(page.getByRole("heading", { name: "진단 문제 1002" })).toBeVisible();
  await expect(page.getByText("diagnosis debug", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "진단 Run 실행 결과" })).toHaveCount(0);
});

test("Run 오류를 표시해도 편집 코드를 유지하며 자동 제출하지 않는다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  await page.route("**/api/v1/diagnoses/120/problems/1001/runs", (route) => route.fulfill({ status: 429,
    json: { success: false, error: { code: "RUN_IN_PROGRESS", message: "이미 실행 중입니다." } } }));
  await page.goto("/diagnoses/120");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// keep diagnosis answer");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByRole("region", { name: "진단 Run 실행 결과" }).getByRole("alert")).toContainText("이미 실행 중입니다.");
  await expect(page.locator(".view-lines")).toContainText("// keep diagnosis answer");
  expect(state.posts).toHaveLength(0);
  expect(state.finishes).toBe(0);
});
