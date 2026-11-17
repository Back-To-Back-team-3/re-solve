import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

for (const linkUrl of ["/mypage?tab=skill", "/me/tag-levels/2"]) {
  test(`${linkUrl} 알림을 누르면 스킬 탭이 열린다`, async ({ page }) => {
    await mockPracticePage(page);
    await page.route("**/api/v1/notifications/unread-count", (route) => route.fulfill({ json: { success: true, data: { unreadCount: 1 } } }));
    await page.route("**/api/v1/notifications/1/read", (route) => route.fulfill({ json: { success: true, data: null } }));
    await page.route("**/api/v1/notifications?*", (route) => route.fulfill({ json: { success: true, data: { content: [{
      notificationId: "1", title: "태그 레벨 알림", content: "해시 레벨이 변경되었습니다.", isRead: false,
      createdAt: "2026-11-16T12:00:00+09:00", linkUrl,
    }] } } }));
    await page.goto("/login");
    await page.getByRole("button", { name: "알림", exact: true }).click();
    await page.getByRole("dialog").getByText("태그 레벨 알림", { exact: true }).click();
    await expect(page).toHaveURL(/\/mypage\?tab=skill$/);
    await expect(page.getByRole("button", { name: "스킬", exact: true })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByText("태그별 진단 레벨", { exact: true })).toBeVisible();
  });
}

test("기존 알림 주소로 직접 접근하거나 새로고침해도 스킬 탭을 연다", async ({ page }) => {
  await mockPracticePage(page);
  await page.goto("/me/tag-levels/2");
  await expect(page).toHaveURL(/\/mypage\?tab=skill$/);
  await page.reload();
  await expect(page.getByRole("button", { name: "스킬", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("태그별 진단 레벨", { exact: true })).toBeVisible();
});
