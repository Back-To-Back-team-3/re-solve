import { expect, test, type Page } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";
import { mockApi } from "./api-fixture";
import { assignedProblemTimeoutMs } from "@/services/assigned-problem-transport";

const path = "/api/v1/studies/me/assigned-problems/1001";
async function mockLocked(page: Page) {
  await mockPracticePage(page);
  await page.route("**/api/v1/members/me/tag-levels", (route) =>
    route.fulfill({ json: { success: true, data: { content: [] } } }));
}

test("배정 응답이 멈추면 기한 후 잠금 동작을 복구하고 새 조회로 다시 확인한다", async ({ page }) => {
  await mockLocked(page);
  let release = () => {}, retry = false, started = false;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route(`**${path}`, async (route) => {
    started = true;
    if (!retry) await held;
    return route.fulfill({ json: { success: true, data: { problemId: "1001", assigned: true } } }).catch(() => {});
  });
  try {
    await page.goto("/problems/1001");
    await expect.poll(() => started).toBe(true);
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === path);
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible({ timeout: assignedProblemTimeoutMs + 5000 });
    await aborted;
    await expect(page.getByRole("button", { name: "배열 Lv.1로 시작", exact: true })).toBeEnabled();
    await expect(page.getByRole("link", { name: "진단 테스트로 레벨 올리기", exact: true })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
    retry = true; release();
    await page.getByRole("button", { name: "레벨 다시 확인", exact: true }).click();
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  } finally { release(); }
});

test("응답 헤더 뒤 JSON 본문이 지연돼도 기한을 적용하고 늦은 본문으로 잠금을 풀지 않는다", async ({ page }) => {
  await mockLocked(page);
  // 헤더 수신 이후의 대기와 취소를 무시하는 늦은 본문까지 네트워크 경계에서 재현한다.
  await page.addInitScript(({ path }) => {
    const originalFetch = window.fetch.bind(window);
    const bodies: (() => void)[] = [];
    let retry = false;
    window.addEventListener("test:release-assigned-body", () => { retry = true; bodies.forEach((release) => release()); });
    window.fetch = (input, init) => {
      const url = input instanceof Request ? input.url : String(input);
      if (new URL(url, location.origin).pathname !== path) return originalFetch(input, init);
      const data = { success: true, data: { problemId: "1001", assigned: true } };
      if (retry) return Promise.resolve(Response.json(data));
      const body = new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode('{"success":true,"data":'));
        bodies.push(() => { controller.enqueue(new TextEncoder().encode(JSON.stringify(data.data) + "}")); controller.close(); });
      } });
      return Promise.resolve(new Response(body, { headers: { "Content-Type": "application/json" } }));
    };
  }, { path });
  await page.goto("/problems/1001");
  await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible({ timeout: assignedProblemTimeoutMs + 5000 });
  await page.evaluate(() => new Promise<void>((resolve) => {
    window.dispatchEvent(new Event("test:release-assigned-body"));
    setTimeout(resolve, 0);
  }));
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
  await page.getByRole("button", { name: "레벨 다시 확인", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
});

test("화면을 떠난 배정 조회의 늦은 401은 갱신·재전송을 만들지 않는다", async ({ page }) => {
  await mockApi(page);
  await page.route("**/api/v1/members/me/tag-levels", (route) =>
    route.fulfill({ json: { success: true, data: { content: [] } } }));
  let release = () => {}, requests = 0, finished = 0, refreshes = 0;
  const held = new Promise<void>((resolve) => { release = resolve; });
  page.on("request", (request) => { if (new URL(request.url()).pathname === "/api/v1/auth/token/refresh") refreshes++; });
  await page.route(`**${path}`, async (route) => {
    requests++; await held;
    await route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "이전 조회 만료" } } }).catch(() => {});
    finished++;
  });
  try {
    await page.goto("/problems/1001");
    await expect.poll(() => requests).toBeGreaterThan(0);
    const previousRequests = requests, previousRefreshes = refreshes;
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === path);
    await page.getByRole("link", { name: "← 문제 목록", exact: true }).click(); await aborted;
    release(); await expect.poll(() => finished).toBe(previousRequests);
    expect(requests).toBe(previousRequests); expect(refreshes).toBe(previousRefreshes);
    await expect(page).toHaveURL("/problems");
  } finally { release(); }
});

test("로그아웃은 배정 조회에서 시작한 인증 갱신도 취소한다", async ({ page }) => {
  await mockLocked(page);
  await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ json: { success: true, data: null } }));
  let releaseQuery = () => {}, releaseRefresh = () => {}, started = false, refreshing = false, requests = 0;
  const heldQuery = new Promise<void>((resolve) => { releaseQuery = resolve; });
  const heldRefresh = new Promise<void>((resolve) => { releaseRefresh = resolve; });
  await page.route(`**${path}`, async (route) => {
    started = true; requests++; await heldQuery;
    return route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "갱신 필요" } } }).catch(() => {});
  });
  try {
    await page.goto("/problems/1001");
    await expect.poll(() => started).toBe(true);
    await page.route("**/api/v1/auth/token/refresh", async (route) => {
      refreshing = true; await heldRefresh;
      return route.fulfill({ json: { success: true, data: { accessToken: "old-session-token" } } }).catch(() => {});
    });
    releaseQuery(); await expect.poll(() => refreshing).toBe(true);
    const beforeLogout = requests;
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === "/api/v1/auth/token/refresh");
    await page.getByRole("button", { name: "로그아웃", exact: true }).click(); await aborted;
    await expect(page).toHaveURL(/\/login/);
    releaseRefresh();
    expect(requests).toBe(beforeLogout);
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  } finally { releaseQuery(); releaseRefresh(); }
});

test("같은 문서의 로그인 콜백은 이전 회원의 갱신을 취소하고 새 토큰을 유지한다", async ({ page }) => {
  await mockLocked(page);
  let releaseQuery = () => {}, releaseRefresh = () => {};
  let started = false, refreshing = false, switched = false;
  const heldQuery = new Promise<void>((resolve) => { releaseQuery = resolve; });
  const heldRefresh = new Promise<void>((resolve) => { releaseRefresh = resolve; });
  const newAuthorizations: (string | undefined)[] = [];
  await page.route(`**${path}`, async (route) => {
    if (switched) {
      newAuthorizations.push(route.request().headers().authorization);
      return route.fulfill({ json: { success: true, data: { problemId: "1001", assigned: false } } });
    }
    started = true;
    await heldQuery;
    return route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "갱신 필요" } } }).catch(() => {});
  });
  try {
    await page.goto("/problems/1001");
    await expect.poll(() => started).toBe(true);
    await page.route("**/api/v1/auth/token/refresh", async (route) => {
      refreshing = true;
      await heldRefresh;
      return route.fulfill({ json: { success: true, data: { accessToken: "old-session-token" } } }).catch(() => {});
    });
    releaseQuery();
    await expect.poll(() => refreshing).toBe(true);
    await page.route("**/api/v1/auth/token", (route) => {
      switched = true;
      return route.fulfill({ json: { success: true, data: { accessToken: "new-member-token", member: { isNewMember: false } } } });
    });
    await page.route("**/api/v1/members/me", (route) => {
      newAuthorizations.push(route.request().headers().authorization);
      return route.fulfill({ json: { success: true, data: {
        memberId: "8", nickname: "새 회원", role: "USER", status: "ACTIVE",
        githubLogin: "new-member", profileImageUrl: null, email: null, hasLearningProfile: true,
        createdAt: "2026-11-13T00:00:00+09:00",
      } } });
    });
    const aborted = page.waitForEvent("requestfailed", (request) => new URL(request.url()).pathname === "/api/v1/auth/token/refresh");
    // Next 개발 서버의 공개 라우터로 이동해 기존 문서·스토어·진행 중 요청을 유지한다.
    await page.evaluate(() => {
      if (!("nd" in window)) throw new Error("Next 개발 라우터가 없습니다.");
      const debug = window.nd;
      if (!debug || typeof debug !== "object" || !("router" in debug)) throw new Error("라우터가 없습니다.");
      const router = debug.router;
      if (!router || typeof router !== "object" || !("push" in router) || typeof router.push !== "function") throw new Error("push가 없습니다.");
      document.documentElement.dataset.callbackDocument = "same-document";
      router.push("/auth/callback?loginCode=switch-member&redirectPath=/problems/1001");
    });
    await aborted;
    await expect(page.getByRole("heading", { name: "레벨 잠금" })).toBeVisible();
    releaseRefresh();
    await page.getByRole("button", { name: "레벨 다시 확인", exact: true }).click();
    await expect.poll(() => newAuthorizations.length).toBeGreaterThanOrEqual(3);
    expect(newAuthorizations.every((value) => value === "Bearer new-member-token")).toBe(true);
    await expect(page.locator("html")).toHaveAttribute("data-callback-document", "same-document");
    await expect(page.getByRole("textbox", { name: "코드 편집기" })).toHaveCount(0);
  } finally { releaseQuery(); releaseRefresh(); }
});
