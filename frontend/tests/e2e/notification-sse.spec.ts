import { expect, test } from "@playwright/test";

test("인증 복원 후 SSE를 연결하고 재연결·로그아웃·다시 로그인 시 세션을 분리한다", async ({ page }) => {
  let release!: () => void;
  const restored = new Promise<void>((resolve) => { release = resolve; });
  let signedIn = true;
  const connections: { authorization: string | undefined; lastId: string | undefined }[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { success: true, data } });
    if (path === "/api/v1/auth/token/refresh") {
      await restored;
      return signedIn ? ok({ accessToken: "session-token", expiresIn: 3600 }) : route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED" } } });
    }
    if (path === "/api/v1/auth/logout") { signedIn = false; return ok(null); }
    if (path === "/api/v1/members/me") return ok({ memberId: "7", nickname: "테스터", role: "USER", status: "ACTIVE" });
    if (path === "/api/v1/notifications/subscribe") {
      const headers = route.request().headers();
      connections.push({ authorization: headers.authorization, lastId: headers["last-event-id"] });
      return route.fulfill({ contentType: "text/event-stream", body: 'id: 42\nevent: heartbeat\ndata: {}\n\n' });
    }
    if (path === "/api/v1/notifications/unread-count") return ok({ unreadCount: 0 });
    return ok({ content: [] });
  });
  await page.goto("/login");
  await expect(page.getByRole("link", { name: "로그인", exact: true })).toBeVisible();
  expect(connections).toHaveLength(0);
  release();
  await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
  await expect.poll(() => connections.length).toBeGreaterThanOrEqual(2);
  expect(connections[0]).toEqual({ authorization: "Bearer session-token", lastId: undefined });
  expect(connections[1].lastId).toBe("42");
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await expect(page.getByRole("button", { name: "알림", exact: true })).toHaveCount(0);
  const before = connections.length;
  await page.waitForTimeout(3500);
  expect(connections).toHaveLength(before);
  signedIn = true;
  await page.reload();
  await expect.poll(() => connections.length).toBeGreaterThan(before);
  expect(connections[before].lastId).toBeUndefined();
});
