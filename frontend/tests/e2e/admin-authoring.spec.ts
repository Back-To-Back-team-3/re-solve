import { expect, test } from "@playwright/test";
import { adminDetail, mockAdminPage } from "./admin-authoring-fixture";
import { mockPracticePage } from "./practice-fixture";

test("등록·검증 응답 유실은 같은 키·본문으로 재요청하고 결과 반영을 구분한다", async ({ page }) => {
  await mockAdminPage(page);
  const writes: { key: string | undefined; body: unknown }[] = [];
  const validations: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/admin/problems", async (route) => {
    writes.push({ key: route.request().headers()["idempotency-key"], body: JSON.parse(route.request().postData() ?? "null") });
    if (writes.length === 1) await route.abort();
    else await route.fulfill({ status: 201, json: { success: true, data: { problemId: "1001", problemRevisionId: "2001", revisionNumber: 1, rowVersion: 1 } } });
  });
  await page.route("**/api/v1/admin/problems/1001/revisions/2001/validations", async (route) => {
    validations.push({ key: route.request().headers()["idempotency-key"], body: JSON.parse(route.request().postData() ?? "null") });
    if (validations.length === 1) await route.abort();
    else await route.fulfill({ status: 202, json: { success: true, data: { validationId: validations.length > 2 ? "3002" : "3001" } } });
  });
  let applied = false;
  let detailReads = 0;
  await page.route("**/api/v1/admin/problems/1001", (route) => {
    detailReads++;
    return route.fulfill({ json: { success: true, data: { ...adminDetail,
      currentProblemRevisionId: applied ? "2001" : null, supportedLanguages: applied ? ["java"] : [],
    } } });
  });
  await page.route("**/api/v1/admin/problems/validations/300*", (route) => route.fulfill({ json: { success: true, data: {
    validationId: route.request().url().split("/").at(-1), problemId: "1001", problemRevisionId: "2001", language: "java", status: route.request().url().endsWith("3001") ? "REJECTED" : "PASSED",
    resultApplied: applied, tests: [{ testOrder: 1, verdict: "AC", timeMs: 2, memoryKb: 1024 }],
    maxTimeMs: 2, message: null, currentProblemRevisionId: applied ? "2001" : null, supportedLanguages: applied ? ["java"] : [],
  } } }));
  await page.goto("/admin/problems/new");
  await page.getByLabel("제목", { exact: true }).fill("새 출제");
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect(page.locator("main").getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect(page).toHaveURL(/\/admin\/problems\/1001$/);
  expect(writes[0]).toEqual(writes[1]);
  await page.getByLabel("검증 코드", { exact: true }).fill("class Solution {}\n");
  await page.getByRole("button", { name: "검증 접수·재요청" }).click();
  await expect(page.locator("main").getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "검증 접수·재요청" }).click();
  await expect(page.getByText("java · REJECTED · 결과 반영: 대기")).toBeVisible();
  expect(validations[0]).toEqual(validations[1]);
  await page.getByLabel("검증 코드", { exact: true }).fill("class Solution { /* corrected */ }\n");
  await page.getByRole("button", { name: "검증 접수·재요청" }).click();
  await expect(page.getByText("java · PASSED · 결과 반영: 대기")).toBeVisible();
  expect(validations[2].key).not.toBe(validations[1].key);
  const readsBeforeApplied = detailReads;
  applied = true;
  await page.getByRole("button", { name: "검증 상태 새로고침" }).click();
  await expect(page.getByText("java · PASSED · 결과 반영: 완료")).toBeVisible();
  await expect(page.getByText("현재 사용 회차: 2001", { exact: true })).toBeVisible();
  await expect(page.getByText("지원 언어: java", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "검증 #3002", exact: true })).toBeVisible();
  await expect(page.getByLabel("검증 코드", { exact: true })).toHaveValue("class Solution { /* corrected */ }\n");
  await expect.poll(() => detailReads).toBe(readsBeforeApplied + 1);
});

test("새 회차 요청은 조회 전용 파일 ID를 제외하고 버전 충돌을 안내한다", async ({ page }) => {
  await mockAdminPage(page);
  let request: unknown;
  await page.route("**/api/v1/admin/problems/1001/revisions", async (route) => {
    request = JSON.parse(route.request().postData() ?? "null");
    await route.fulfill({ status: 409, json: { success: false, error: { code: "PROBLEM_VERSION_CONFLICT", message: "최신 상태를 확인해 주세요." } } });
  });
  await page.goto("/admin/problems/1001/edit");
  await page.getByLabel("제목", { exact: true }).fill("수정 제목");
  await page.getByRole("button", { name: "새 회차 저장·재요청" }).click();
  await expect(page.locator("main").getByRole("alert")).toHaveText("최신 상태를 확인해 주세요.");
  expect(request).toMatchObject({ rowVersion: 3, title: "수정 제목" });
  expect(JSON.stringify(request)).not.toContain("testAssetId");
  expect(request).not.toHaveProperty("scope");
});

test("비관리자는 출제 폼을 열 수 없다", async ({ page }) => {
  await mockPracticePage(page);
  await page.goto("/admin/problems/new");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("button", { name: "문제 등록·재요청" })).toHaveCount(0);
});

test("공개·숨김 테스트를 JSON으로 편집하고 추가·삭제하며 같은 본문으로 재요청한다", async ({ page }) => {
  await mockAdminPage(page);
  const writes: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/admin/problems", async (route) => {
    writes.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    await route.abort();
  });
  await page.goto("/admin/problems/new");
  await page.getByLabel("제목", { exact: true }).fill("항목별 테스트");
  await expect(page.getByLabel("공개·숨김 테스트 (JSON)", { exact: true })).toHaveCount(0);
  await page.getByLabel("테스트 1 입력값 JSON", { exact: true }).fill('{"a":');
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect(page.locator("main").getByRole("alert")).toContainText("유효한 JSON");
  expect(writes).toHaveLength(0);
  await page.getByLabel("테스트 1 입력값 JSON", { exact: true }).fill('{"a":3}');
  await page.getByLabel("테스트 1 기대 출력 JSON", { exact: true }).fill("32");
  await page.getByRole("button", { name: "테스트 추가 (2/50)", exact: true }).click();
  await page.getByLabel("테스트 3 입력값 JSON", { exact: true }).fill('{"a":9}');
  await page.getByLabel("테스트 3 기대 출력 JSON", { exact: true }).fill("18");
  await page.getByRole("button", { name: "테스트 2 삭제", exact: true }).click();
  await page.getByLabel("테스트 2 공개 여부", { exact: true }).selectOption("sample");
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect(page.locator("main").getByRole("alert")).toHaveText("공개·숨김 테스트가 각각 한 개 이상 필요합니다.");
  expect(writes).toHaveLength(0);
  await page.getByLabel("테스트 2 공개 여부", { exact: true }).selectOption("hidden");
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect.poll(() => writes.length).toBe(1);
  await expect(page.getByRole("button", { name: "문제 등록·재요청" })).toBeEnabled();
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect.poll(() => writes.length).toBe(2);
  expect(writes[0]).toEqual(writes[1]);
  expect(writes[0].body).toMatchObject({ testCases: [
    { isSample: true, argumentTexts: { a: "3" }, expectedReturnText: "32" },
    { isSample: false, argumentTexts: { a: "9" }, expectedReturnText: "18" },
  ] });
});

test("본문에서 이미지 첨부를 재요청하고 대기 중 편집한 커서 위치에 삽입한다", async ({ page }) => {
  await mockAdminPage(page);
  const keys: (string | undefined)[] = [];
  let completeUpload: (() => void) | undefined;
  const uploadPending = new Promise<void>((resolve) => { completeUpload = resolve; });
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=", "base64");
  await page.route("**/api/v1/admin/problems/images", async (route) => {
    keys.push(route.request().headers()["idempotency-key"]);
    expect(route.request().headers()["content-type"]).toContain("multipart/form-data; boundary=");
    expect(route.request().headers()["authorization"]).toBe("Bearer test-access");
    if (keys.length === 1) await route.abort();
    else { await uploadPending; await route.fulfill({ status: 201, json: { success: true, data: { imageId: "701" } } }); }
  });
  await page.route("**/api/v1/admin/problems/images/701/content", async (route) => {
    expect(route.request().headers()["authorization"]).toBe("Bearer test-access");
    await route.fulfill({ contentType: "image/png", body: png });
  });
  await page.goto("/admin/problems/new");
  await page.getByLabel("제목", { exact: true }).fill("이미지 작성 유지");
  await page.getByLabel("업로드 이미지").setInputFiles({ name: "bad.txt", mimeType: "text/plain", buffer: Buffer.from("bad") });
  await page.getByRole("button", { name: "이미지 첨부·재요청" }).click();
  await expect(page.getByText("PNG·JPEG·WebP, 5MiB 이하 이미지를 선택해 주세요.")).toBeVisible();
  expect(keys).toHaveLength(0);
  await page.getByLabel("업로드 이미지").setInputFiles({ name: "image.png", mimeType: "image/png", buffer: png });
  await page.getByRole("button", { name: "이미지 첨부·재요청" }).click();
  await expect.poll(() => keys.length).toBe(1);
  await expect(page.getByRole("button", { name: "이미지 첨부·재요청" })).toBeEnabled();
  await page.getByRole("button", { name: "이미지 첨부·재요청" }).click();
  await expect(page.getByRole("button", { name: "문제 등록·재요청" })).toBeDisabled();
  await page.getByLabel("문제 설명", { exact: true }).fill("수정 앞뒤");
  await page.getByLabel("문제 설명", { exact: true }).press("ArrowLeft");
  completeUpload?.();
  await expect(page.getByLabel("문제 설명", { exact: true })).toHaveValue("수정 앞\n![이미지](image:701)\n뒤");
  await page.getByText("첨부 이미지 미리보기", { exact: true }).click();
  await expect(page.getByRole("img", { name: "업로드 미리보기" })).toBeVisible();
  expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
  await expect(page.getByLabel("제목", { exact: true })).toHaveValue("이미지 작성 유지");
  await expect(page.getByRole("button", { name: "문제 등록·재요청" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "이미지 첨부·재요청" })).toBeDisabled();
});
