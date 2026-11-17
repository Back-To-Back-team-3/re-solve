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

for (const { path, tab, label } of [
  { path: "/mypage?tab=submissions", tab: "submissions", label: "제출 기록" },
  { path: "/mypage?tab=skill", tab: "skill", label: "스킬" },
  { path: "/me/tag-levels/2", tab: "skill", label: "스킬" },
]) {
  test(`${path} 비로그인 접근 후 로그인해도 목적 탭을 유지한다`, async ({ page }) => {
    await mockPracticePage(page);
    let signedIn = false;
    await page.route("**/api/v1/auth/token/refresh", (route) => route.fulfill(signedIn
      ? { json: { success: true, data: { accessToken: "restored-token", expiresIn: 3600 } } }
      : { status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } } }));
    await page.route("**/api/v1/auth/github?*", (route) => {
      const redirectPath = new URL(route.request().url()).searchParams.get("redirectPath");
      expect(redirectPath).toBe(`/mypage?tab=${tab}`);
      return route.fulfill({ status: 302, headers: {
        location: `/auth/callback?loginCode=test&redirectPath=${encodeURIComponent(redirectPath!)}`,
      } });
    });
    await page.route("**/api/v1/auth/token", (route) => {
      signedIn = true;
      return route.fulfill({ json: { success: true, data: {
        accessToken: "logged-in-token", member: { isNewMember: false },
      } } });
    });
    await page.goto(path);
    await expect(page).toHaveURL(`/login?returnTo=${encodeURIComponent(`/mypage?tab=${tab}`)}`);
    await page.getByRole("button", { name: "GitHub으로 로그인", exact: true }).click();
    await expect(page).toHaveURL(`/mypage?tab=${tab}`);
    await expect(page.getByRole("button", { name: label, exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.reload();
    await expect(page.getByRole("button", { name: label, exact: true })).toHaveAttribute("aria-pressed", "true");
  });
}
