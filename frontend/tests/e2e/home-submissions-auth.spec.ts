import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

for (const signedIn of [true, false]) {
  test(`인증 복구 전 조회를 막고 ${signedIn ? "로그인 후 조회한다" : "비로그인은 조회하지 않는다"}`, async ({ page }) => {
    await mockPracticePage(page);
    let release!: () => void;
    const restored = new Promise<void>((resolve) => { release = resolve; });
    const requests: (string | undefined)[] = [];
    await page.route("**/api/v1/auth/token/refresh", async (route) => {
      await restored;
      return route.fulfill(signedIn
        ? { json: { success: true, data: { accessToken: "restored-token", expiresIn: 3600 } } }
        : { status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } } });
    });
    await page.route("**/api/v1/me/submissions?*", (route) => {
      requests.push(route.request().headers().authorization);
      return route.fulfill({ json: { success: true, data: { content: [] } } });
    });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "최근 제출", exact: true })).toBeVisible();
    expect(requests).toHaveLength(0);
    release();
    if (signedIn) {
      await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
      await expect.poll(() => requests.length).toBeGreaterThan(0);
      expect(requests.every((header) => header === "Bearer restored-token")).toBe(true);
      await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ json: { success: true, data: null } }));
      await page.getByRole("button", { name: "로그아웃", exact: true }).click();
      await expect(page).toHaveURL(/\/login$/);
      await page.getByRole("link", { name: /Re:Solve/ }).click();
    }
    await expect(page.getByText("로그인하면 최근 제출 내역을 확인할 수 있어요.")).toBeVisible();
    const count = requests.length;
    await page.waitForTimeout(300);
    expect(requests).toHaveLength(count);
  });
}

test("로그아웃하면 이전 제출을 지우고 다른 회원 로그인 후 새 내역을 조회하며 실패 시 재시도한다", async ({ page }) => {
  await mockPracticePage(page);
  let memberId = "7";
  let fail = false;
  const members: string[] = [];
  await page.route("**/api/v1/members/me", (route) => route.fulfill({ json: { success: true, data: {
    memberId, nickname: `회원${memberId}`, role: "USER", status: "ACTIVE",
  } } }));
  await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ json: { success: true, data: null } }));
  await page.route("**/api/v1/auth/token", (route) => route.fulfill({ json: { success: true, data: {
    accessToken: "second-member-token", member: { isNewMember: false },
  } } }));
  await page.route("**/api/v1/me/submissions?*", (route) => {
    members.push(memberId);
    if (fail) return route.fulfill({ status: 503, json: { success: false, error: { code: "UNAVAILABLE", message: "제출 목록 조회 실패" } } });
    return route.fulfill({ json: { success: true, data: { content: [{
      submissionId: memberId, problemId: `회원${memberId}의문제`, status: "COMPLETED", verdict: "AC",
      submittedAt: "2026-11-16T00:00:00+09:00",
    }] } } });
  });
  await page.goto("/");
  await expect(page.getByRole("link", { name: /회원7의문제/ })).toBeVisible();
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole("link", { name: /Re:Solve/ }).click();
  await expect(page.getByText("로그인하면 최근 제출 내역을 확인할 수 있어요.")).toBeVisible();
  await expect(page.getByRole("link", { name: /회원7의문제/ })).toHaveCount(0);
  memberId = "8";
  fail = true;
  await page.goto("/auth/callback?loginCode=test&redirectPath=/");
  await expect(page.getByText("제출 목록 조회 실패", { exact: true })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: /다시 시도/ }).click();
  await expect(page.getByRole("link", { name: /회원8의문제/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /회원7의문제/ })).toHaveCount(0);
  expect(members).toContain("7");
  expect(members).toContain("8");
});
