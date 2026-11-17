import { expect, test } from "@playwright/test";
import { mockDiagnosis } from "./diagnosis-fixture";

test("홈에서 Lv.1 진입·문제 필터·실제 진단 시작을 함께 유지한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  let entered = false;
  let entries = 0;
  await page.route("**/api/v1/members/me/tag-levels", async (route) => {
    if (route.request().method() === "POST") {
      entries++;
      expect(route.request().postDataJSON()).toEqual({ tagId: "1" });
      entered = true;
      return route.fulfill({ status: 201, json: { success: true, data: { tagId: "1", level: "LV1" } } });
    }
    return route.fulfill({ json: { success: true, data: { content: entered ? [{
      tagId: "1", level: "LV1", accessibleMaxDifficulty: 1, achievements: [], nextPromotion: null,
      diagnosis: { isEligible: true, cooldownEndsAt: null, inProgressDiagnosisId: state.started ? "120" : null },
    }] : [] } } });
  });
  await page.goto("/");
  const card = page.locator("article.pet-card").filter({ has: page.getByRole("heading", { name: "배열", exact: true }) });
  await card.getByRole("button", { name: "알 부화하기" }).click();
  await page.getByRole("button", { name: "부화시키기", exact: true }).click();
  await expect(card).toContainText("LEVEL 1");
  const filtered = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/problems" && url.searchParams.get("tagIds") === "1";
  });
  await page.getByRole("link", { name: "문제 풀러 가기" }).click();
  await filtered;
  await expect(page.getByRole("combobox", { name: "모든 알고리즘" })).toHaveValue("1");
  await page.goto("/");
  await card.getByRole("link", { name: "진단", exact: true }).click();
  await expect(page).toHaveURL(/\/diagnoses\?tagId=1$/);
  await page.getByRole("button", { name: "재응시", exact: true }).click();
  await expect(page).toHaveURL(/\/diagnoses\/120$/);
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  expect(entries).toBe(1);
});

test("진단 레벨 변경 알림이 홈의 공통 레벨 조회를 갱신한다", async ({ page }) => {
  const state = await mockDiagnosis(page);
  state.started = true;
  await page.goto("/");
  await expect(page.getByText("Lv.0", { exact: true })).toBeVisible();
  state.detail = { ...state.detail, status: "SCORED", result: { score: 3, resultLevel: "LV2", appliedLevel: "LV2" } };
  await page.evaluate(() => window.dispatchEvent(new Event("resolve:level-changed")));
  await expect(page.getByText("Lv.2", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "내 실력 진단" })).toHaveAttribute("href", "/diagnoses?tagId=1");
});
