import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

for (const width of [1128, 1440]) {
  test(`폭 ${width}에서 알림창 제목과 모두 읽음 버튼은 헤더 위에 표시된다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 852 });
    await mockPracticePage(page);
    await page.route("**/api/v1/notifications/unread-count", (route) => route.fulfill({ json: { success: true, data: { unreadCount: 1 } } }));
    await page.route("**/api/v1/notifications?*", (route) => route.fulfill({ json: { success: true, data: { content: [{
      notificationId: "1", title: "채점 완료", content: "문제 채점이 완료되었습니다.", isRead: false,
      createdAt: new Date().toISOString(), linkUrl: "/problems/1001",
    }] } } }));
    await page.goto("/login");
    await page.getByRole("button", { name: "알림", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("button", { name: "모두 읽음", exact: true })).toBeVisible();
    for (const element of [dialog.getByText("알림", { exact: true }), dialog.getByRole("button", { name: "모두 읽음", exact: true })]) {
      await expect.poll(() => element.evaluate((target) => {
        const rect = target.getBoundingClientRect();
        return target.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
      })).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
  });
}
