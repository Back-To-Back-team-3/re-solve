import { test, expect } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";
import { mockApi } from "./api-fixture";

const level = (tagId: string, maximum: number) => ({
  tagId, level: `LV${maximum}`, accessibleMaxDifficulty: maximum, achievements: [],
  diagnosis: { isEligible: true, cooldownEndsAt: null, inProgressDiagnosisId: null }, nextPromotion: null,
});

test("미진입 문제는 잠기고 명시적인 Lv.1 시작 후에만 실행 화면을 연다", async ({ page }) => {
  await mockPracticePage(page);
  let entered = false;
  let requests = 0;
  await page.route("**/api/v1/members/me/tag-levels", async (route) => {
    if (route.request().method() === "POST") {
      requests++;
      expect(route.request().postDataJSON()).toEqual({ tagId: "1" });
      entered = true;
      await route.fulfill({ status: 201, json: { success: true, data: { tagId: "1", level: "LV1" } } });
    } else await route.fulfill({ json: { success: true, data: { content: entered ? [level("1", 1)] : [] } } });
  });
  await page.goto("/problems/1001");
  await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
  await expect(page.getByRole("button", { name: "제출", exact: true })).toHaveCount(0);
  expect(requests).toBe(0);
  await page.getByRole("button", { name: "배열 Lv.1로 시작" }).click();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  expect(requests).toBe(1);
});

test("진입 실패는 잠금을 유지하고 중복 진입은 최신 레벨로 재조회한다", async ({ page }) => {
  await mockPracticePage(page);
  let attempts = 0;
  await page.route("**/api/v1/members/me/tag-levels", async (route) => {
    if (route.request().method() === "POST") {
      attempts++;
      await route.fulfill({ status: attempts === 1 ? 503 : 409, json: { success: false, error: {
        code: attempts === 1 ? "COMMON_DEPENDENCY_UNAVAILABLE" : "LEVEL_ALREADY_ENTERED", message: "진입 실패",
      } } });
    } else await route.fulfill({ json: { success: true, data: { content: attempts < 2 ? [] : [level("1", 1)] } } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "배열 Lv.1로 시작" }).click();
  await expect(page.getByRole("region", { name: "문제 레벨 제한" }).getByRole("alert")).toContainText("진입 실패");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  await page.getByRole("button", { name: "배열 Lv.1로 시작" }).click();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
});

test("레벨 조회 실패·진단 중 Lv.0·다중 태그 부족은 실행을 허용하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  let fail = true;
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, tags: [...problemDetail.tags, { tagId: "2", name: "정렬" }],
  } } }));
  await page.route("**/api/v1/members/me/tag-levels", (route) => fail
    ? route.fulfill({ status: 503, json: { success: false, error: { code: "UNAVAILABLE", message: "레벨 서버 오류" } } })
    : route.fulfill({ json: { success: true, data: { content: [level("1", 1), level("2", 0)] } } }));
  await page.goto("/problems/1001");
  await expect(page.getByText("레벨을 확인하지 못했습니다.", { exact: false })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  fail = false;
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.getByText("정렬: LV0 · 필요 Lv. 1")).toBeVisible();
  await expect(page.getByRole("button", { name: /Lv.1로 시작/ })).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
});

test("홈 대표 알고리즘 링크가 실제 목록 요청에 tagIds 필터를 전달한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/");
  await page.getByRole("button", { name: "정렬 대표 펫 선택" }).click();
  const filtered = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/problems" && url.searchParams.get("tagIds") === "2";
  });
  await page.getByRole("link", { name: "문제 풀러 가기" }).click();
  await filtered;
  await expect(page.getByRole("combobox", { name: "모든 알고리즘" })).toHaveValue("2");
  await expect(page.getByRole("link", { name: "고정 회차 배열 합", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "고정 회차 배열 최댓값", exact: true })).toBeVisible();
});

test("목록은 잠긴 문제도 표시하고 부족한 태그와 필요 레벨을 안내한다", async ({ page }) => {
  await mockApi(page, { handle: async (route, path) => {
    if (path !== "/api/v1/members/me/tag-levels") return false;
    await route.fulfill({ json: { success: true, data: { content: [level("1", 1)] } } });
    return true;
  } });
  await page.goto("/problems");
  await expect(page.getByRole("link", { name: "진단 테스트로 레벨 올리기" })).toHaveAttribute("href", "/mypage");
  await expect(page.getByRole("link", { name: "고정 회차 배열 최댓값", exact: true })).toBeVisible();
  await expect(page.getByText("레벨 잠금 · 정렬 Lv.2 필요")).toBeVisible();
  await expect(page.getByText("풀이 가능", { exact: true })).toBeVisible();
});
