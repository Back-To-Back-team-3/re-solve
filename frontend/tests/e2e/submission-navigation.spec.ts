import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

for (const path of ["/", "/dashboard"]) {
  test(`${path} 최근 제출 전체 보기는 제출 기록 탭으로 이동한다`, async ({ page }) => {
    await mockPracticePage(page);
    await page.route("**/api/v1/studies/me?*", (route) => route.fulfill({ json: { success: true, data: { content: [] } } }));
    await page.goto(path);
    const section = page.locator("section").filter({ has: page.getByRole("heading", { name: "최근 제출", exact: true }) });
    await section.getByRole("link", { name: /전체 보기/ }).click();
    await expect(page).toHaveURL(/\/mypage\?tab=submissions$/);
    await expect(page.getByRole("button", { name: "제출 기록", exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.reload();
    await expect(page.getByRole("button", { name: "제출 기록", exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: "프로필", exact: true }).click();
    await expect(page).toHaveURL(/tab=profile$/);
    await page.goBack();
    await expect(page.getByRole("button", { name: "제출 기록", exact: true })).toHaveAttribute("aria-pressed", "true");
  });
}

test("알 수 없는 마이페이지 탭은 프로필로 표시한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.goto("/mypage?tab=unknown");
  await expect(page.getByRole("button", { name: "프로필", exact: true })).toHaveAttribute("aria-pressed", "true");
});
