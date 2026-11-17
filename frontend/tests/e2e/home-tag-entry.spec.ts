import { test, expect } from "@playwright/test";
import { mockApi } from "./api-fixture";

for (const outcome of ["success", "duplicate", "failure"] as const) {
  test(`홈 부화 ${outcome}: 서버 레벨을 조회하고 재방문에도 반영한다`, async ({ page }) => {
    let entered = false;
    let posts = 0;
    await mockApi(page, { handle: async (route, path, method) => {
      if (path !== "/api/v1/members/me/tag-levels") return false;
      if (method === "POST") {
        posts++;
        expect(route.request().postDataJSON()).toEqual({ tagId: "1" });
        entered = outcome !== "failure";
        await route.fulfill(outcome === "success"
          ? { status: 201, json: { success: true, data: { tagId: "1", level: "LV1" } } }
          : { status: outcome === "duplicate" ? 409 : 503, json: { success: false, error: {
            code: outcome === "duplicate" ? "LEVEL_ALREADY_ENTERED" : "COMMON_DEPENDENCY_UNAVAILABLE", message: "부화 요청 실패",
          } } });
      } else await route.fulfill({ json: { success: true, data: { content: entered ? [{
        tagId: "1", level: "LV1", accessibleMaxDifficulty: 1, achievements: [],
        diagnosis: { isEligible: true, cooldownEndsAt: null, inProgressDiagnosisId: null }, nextPromotion: null,
      }] : [] } } });
      return true;
    } });
    await page.goto("/");
    const card = page.locator("article.pet-card").filter({ has: page.getByRole("heading", { name: "배열", exact: true }) });
    await card.getByRole("button", { name: "알 부화하기" }).click();
    await page.getByRole("button", { name: "부화시키기", exact: true }).click();
    if (outcome === "failure") {
      await expect(page.getByRole("dialog").getByRole("alert")).toHaveText("부화 요청 실패");
    } else {
      await expect(card.getByRole("link", { name: "문제 풀기", exact: true })).toBeVisible();
    }
    await page.reload();
    await expect(card.getByRole("button", { name: "알 부화하기" })).toHaveCount(outcome === "failure" ? 1 : 0);
    if (outcome !== "failure") await expect(card).toContainText("LEVEL 1");
    expect(posts).toBe(1);
  });
}

test("조회 실패를 미진입으로 표시하지 않고 기존 Lv.0과 마스터도 서버 값으로 표시한다", async ({ page }) => {
  let fail = true;
  await mockApi(page, { handle: async (route, path) => {
    if (path !== "/api/v1/members/me/tag-levels") return false;
    await route.fulfill(fail
      ? { status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "레벨 조회 실패" } } }
      : { json: { success: true, data: { content: [
        { tagId: "1", level: "LV0", accessibleMaxDifficulty: 0 },
        { tagId: "2", level: "MASTER", accessibleMaxDifficulty: 3 },
      ].map((item) => ({ ...item, achievements: [], nextPromotion: null,
        diagnosis: { isEligible: false, cooldownEndsAt: null, inProgressDiagnosisId: item.tagId === "1" ? "10" : null },
      })) } } });
    return true;
  } });
  await page.goto("/");
  await expect(page.getByText("레벨 조회 실패", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "알 부화하기" })).toHaveCount(0);
  fail = false;
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page.locator("article.pet-card").filter({ hasText: "정렬" })).toContainText("MASTER");
  await expect(page.getByRole("button", { name: "알 부화하기" })).toHaveCount(0);
});
