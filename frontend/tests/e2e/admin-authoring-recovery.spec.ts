import { expect, test } from "@playwright/test";
import { adminDetail, mockAdminPage } from "./admin-authoring-fixture";

test("언어 전환과 상세 재조회 실패 후에도 검증 초안·최초 재요청 키를 유지한다", async ({ page }) => {
  await mockAdminPage(page);
  let unavailable = false;
  await page.route("**/api/v1/admin/problems/1001", (route) => unavailable
    ? route.fulfill({ status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "일시 조회 장애" } } })
    : route.fulfill({ json: { success: true, data: adminDetail } }));
  const keys: (string | undefined)[] = [];
  const bodies: unknown[] = [];
  await page.route("**/api/v1/admin/problems/1001/revisions/2001/validations", (route) => {
    keys.push(route.request().headers()["idempotency-key"]); bodies.push(route.request().postDataJSON());
    return keys.length === 1 ? route.abort() : route.fulfill({ status: 202, json: { success: true, data: { validationId: "700" } } });
  });
  await page.route("**/api/v1/admin/problems/validations/700", (route) => route.fulfill({ json: { success: true, data: {
    validationId: "700", problemId: "1001", problemRevisionId: "2001", language: "java", status: "FAILED",
    tests: [{ testOrder: 1, verdict: "SYSTEM_ERROR", timeMs: null, memoryKb: null }],
    maxTimeMs: null, message: null, resultApplied: false, supportedLanguages: [], currentProblemRevisionId: null,
  } } }));
  await page.goto("/admin/problems/1001");
  const code = page.getByLabel("검증 코드", { exact: true });
  await code.fill("class Solution { /* java draft */ }");
  await page.getByRole("button", { name: "검증 접수·재요청", exact: true }).click();
  await expect(page.getByRole("button", { name: "검증 접수·재요청", exact: true })).toBeEnabled();
  await page.getByLabel("검증 언어", { exact: true }).selectOption("python3");
  await code.fill("# python draft");
  await page.getByLabel("검증 언어", { exact: true }).selectOption("java");
  await expect(code).toHaveValue("class Solution { /* java draft */ }");
  unavailable = true;
  await page.getByRole("button", { name: "상세 새로고침", exact: true }).click();
  await expect(page.getByText("일시 조회 장애", { exact: true })).toBeVisible();
  await expect(code).toHaveValue("class Solution { /* java draft */ }");
  unavailable = false;
  await page.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.getByText("일시 조회 장애", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "검증 접수·재요청", exact: true }).click();
  await expect(page.getByRole("heading", { name: "검증 #700", exact: true })).toBeVisible();
  expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
  expect(bodies[1]).toEqual(bodies[0]);
  await expect(page.getByText(/테스트 1: SYSTEM_ERROR/)).toBeVisible();
  await page.getByLabel("검증 언어", { exact: true }).selectOption("python3");
  await expect(code).toHaveValue("# python draft");
});

test("버전 충돌 후 최신 서버 내용을 확인하고 작성한 입력으로 새 버전을 접수한다", async ({ page }) => {
  await mockAdminPage(page);
  let version = 3;
  await page.route("**/api/v1/admin/problems/1001", (route) => route.fulfill({ json: { success: true,
    data: { ...adminDetail, rowVersion: version, title: version === 3 ? adminDetail.title : "다른 관리자의 변경" } } }));
  const requests: { key: string | undefined; body: Record<string, unknown> }[] = [];
  await page.route("**/api/v1/admin/problems/1001/revisions", (route) => {
    requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    return requests.length === 1 ? route.fulfill({ status: 409, json: { success: false,
      error: { code: "PROBLEM_VERSION_CONFLICT", message: "최신 상태를 확인해 주세요." } } })
      : route.fulfill({ status: 201, json: { success: true, data: { problemRevisionId: "2002", revisionNumber: 2, rowVersion: 9 } } });
  });
  await page.goto("/admin/problems/1001/edit");
  await page.getByLabel("제목", { exact: true }).fill("보존할 작성 제목");
  await page.getByRole("button", { name: "새 회차 저장·재요청", exact: true }).click();
  await expect(page.getByRole("region", { name: "버전 충돌 복구", exact: true })).toBeVisible();
  version = 8;
  await page.getByRole("button", { name: "최신 서버 내용 확인", exact: true }).click();
  await page.getByRole("button", { name: "현재 입력 유지·최신 버전으로 계속 편집", exact: true }).click();
  await expect(page.getByLabel("제목", { exact: true })).toHaveValue("보존할 작성 제목");
  await page.getByRole("button", { name: "새 회차 저장·재요청", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/problems\/1001$/);
  expect(requests[0].body).toMatchObject({ title: "보존할 작성 제목", rowVersion: 3 });
  expect(requests[1].body).toMatchObject({ title: "보존할 작성 제목", rowVersion: 8 });
  expect(requests[1].key).not.toBe(requests[0].key);
});

test("본문 이미지 조회가 실패해도 편집 내용을 유지하며 이미지만 다시 불러온다", async ({ page }) => {
  await mockAdminPage(page);
  await page.route("**/api/v1/admin/problems/1001", (route) => route.fulfill({ json: { success: true,
    data: { ...adminDetail, description: "![저장된 이미지](image:701)" } } }));
  let unavailable = true;
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=", "base64");
  await page.route("**/api/v1/admin/problems/images/701/content", (route) => unavailable
    ? route.fulfill({ status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "이미지 조회 장애" } } })
    : route.fulfill({ contentType: "image/png", body: png }));
  await page.goto("/admin/problems/1001");
  await expect(page.getByRole("button", { name: "이미지 다시 불러오기", exact: true })).toBeVisible();
  await page.getByLabel("검증 코드", { exact: true }).fill("보관할 초안");
  unavailable = false;
  await page.getByRole("button", { name: "이미지 다시 불러오기", exact: true }).click();
  await expect.poll(() => page.getByRole("img", { name: "저장된 이미지", exact: true })
    .evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(1);
  await expect(page.getByLabel("검증 코드", { exact: true })).toHaveValue("보관할 초안");
  unavailable = true;
  await page.getByRole("link", { name: "최신 회차에서 수정", exact: true }).click();
  await page.getByLabel("제목", { exact: true }).fill("유지할 작성 제목");
  await page.getByRole("button", { name: "image:701 미리보기", exact: true }).click();
  await expect(page.getByRole("tooltip").getByRole("button", { name: "이미지 다시 불러오기", exact: true })).toBeVisible();
  unavailable = false;
  await page.getByRole("tooltip").getByRole("button", { name: "이미지 다시 불러오기", exact: true }).click();
  await expect.poll(() => page.getByRole("tooltip").getByRole("img", { name: "image:701 첨부 이미지", exact: true })
    .evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(1);
  await expect(page.getByLabel("제목", { exact: true })).toHaveValue("유지할 작성 제목");
});
