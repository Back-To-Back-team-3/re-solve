import { expect, test } from "@playwright/test";
import { mockAdminPage } from "./admin-authoring-fixture";
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
  await page.route("**/api/v1/admin/problems/validations/300*", (route) => route.fulfill({ json: { success: true, data: {
    validationId: "3001", problemId: "1001", problemRevisionId: "2001", language: "java", status: route.request().url().endsWith("3001") ? "REJECTED" : "PASSED",
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
  applied = true;
  await page.getByRole("button", { name: "검증 상태 새로고침" }).click();
  await expect(page.getByText("java · PASSED · 결과 반영: 완료")).toBeVisible();
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

test("이미지 형식 오류를 막고 업로드 실패 후 원래 파일·키로 재요청해 인증 미리보기를 읽는다", async ({ page }) => {
  await mockAdminPage(page);
  const keys: (string | undefined)[] = [];
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=", "base64");
  await page.route("**/api/v1/admin/problems/images", async (route) => {
    keys.push(route.request().headers()["idempotency-key"]);
    expect(route.request().headers()["content-type"]).toContain("multipart/form-data; boundary=");
    expect(route.request().headers()["authorization"]).toBe("Bearer test-access");
    if (keys.length === 1) await route.abort();
    else await route.fulfill({ status: 201, json: { success: true, data: { imageId: "701" } } });
  });
  await page.route("**/api/v1/admin/problems/images/701/content", async (route) => {
    expect(route.request().headers()["authorization"]).toBe("Bearer test-access");
    await route.fulfill({ contentType: "image/png", body: png });
  });
  await page.goto("/admin/problems/new");
  await page.getByLabel("업로드 이미지").setInputFiles({ name: "bad.txt", mimeType: "text/plain", buffer: Buffer.from("bad") });
  await page.getByRole("button", { name: "이미지 업로드·재요청" }).click();
  await expect(page.getByText("PNG·JPEG·WebP, 5MiB 이하 이미지를 선택해 주세요.")).toBeVisible();
  expect(keys).toHaveLength(0);
  await page.getByLabel("업로드 이미지").setInputFiles({ name: "image.png", mimeType: "image/png", buffer: png });
  await page.getByRole("button", { name: "이미지 업로드·재요청" }).click();
  await expect.poll(() => keys.length).toBe(1);
  await expect(page.getByRole("button", { name: "이미지 업로드·재요청" })).toBeEnabled();
  await page.getByRole("button", { name: "이미지 업로드·재요청" }).click();
  await expect(page.getByRole("img", { name: "업로드 미리보기" })).toBeVisible();
  expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
  await page.getByRole("button", { name: "설명에 이미지 삽입" }).click();
  await expect(page.getByLabel("문제 설명", { exact: true })).toHaveValue(/image:701/);
});
