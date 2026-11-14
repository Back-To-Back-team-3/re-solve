import { expect, test } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=", "base64");

test("일반 회원이 고정 회차의 본문 이미지를 인증해서 조회하고 새로고침 후에도 표시한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, description: "![문제 도형](image:701)",
  } } }));
  let requests = 0;
  await page.route("**/api/v1/problems/1001/revisions/2001/images/701", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer test-access");
    requests++;
    return route.fulfill({ contentType: "image/png", body: png });
  });
  let adminRequests = 0;
  await page.route("**/api/v1/admin/**", (route) => { adminRequests++; return route.abort(); });
  await page.goto("/problems/1001");
  const image = page.getByRole("img", { name: "문제 도형", exact: true });
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBe(1);
  expect(requests).toBeGreaterThan(0);
  const beforeReload = requests;
  await page.reload();
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBe(1);
  expect(requests).toBeGreaterThan(beforeReload);
  expect(adminRequests).toBe(0);
});

test("본문 이미지 조회 실패를 안내하면서 문제 내용과 편집기는 유지한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, description: "문제 내용 유지\n\n![문제 도형](image:701)",
  } } }));
  await page.route("**/api/v1/problems/1001/revisions/2001/images/701", (route) => route.fulfill({ status: 503,
    json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "이미지 저장소에 연결할 수 없습니다." } },
  }));
  await page.goto("/problems/1001");
  await expect(page.getByText("이미지 저장소에 연결할 수 없습니다.", { exact: false })).toBeVisible();
  await expect(page.getByText("문제 내용 유지", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기", exact: true })).toBeVisible();
});

test("시험 본문 이미지는 일반·관리자 경로 대신 입장 권한을 검사하는 시험 API로 조회한다", async ({ page }) => {
  await mockPracticePage(page);
  const now = new Date().toISOString();
  await page.route("**/api/v1/exams/42/problems", (route) => route.fulfill({ json: { success: true, data: {
    problems: [{ problemId: "1001", title: "시험 문제", body: "![시험 도형](image:701)", score: "100", languages: ["java"] }],
    serverNow: now, personalEndsAt: new Date(Date.now() + 3600000).toISOString(),
  } } }));
  let requests = 0;
  await page.route("**/api/v1/exams/42/problems/1001/images/701", (route) => {
    expect(route.request().headers().authorization).toBe("Bearer test-access");
    requests++;
    return route.fulfill({ contentType: "image/png", body: png });
  });
  let wrongContext = 0;
  await page.route("**/api/v1/problems/*/revisions/*/images/*", (route) => { wrongContext++; return route.abort(); });
  await page.route("**/api/v1/admin/**", (route) => { wrongContext++; return route.abort(); });
  await page.goto("/exams/42/room");
  const image = page.getByRole("img", { name: "시험 도형", exact: true });
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBe(1);
  expect(requests).toBeGreaterThan(0);
  expect(wrongContext).toBe(0);
});
