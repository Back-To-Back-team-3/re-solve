import { test, expect } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

const receipt = {
  submissionId: "2001", judgeAttempt: 1, status: "QUEUED",
  submittedAt: "2026-11-13T00:00:00.000000+09:00", statusUrl: "/api/v1/submissions/2001",
};
const completed = {
  submissionId: "2001", status: "COMPLETED", compileMessage: null,
  effectiveResult: { verdict: "AC", passedCount: 2, totalCount: 2, maxTimeMs: 1, maxMemoryKb: 1024 },
};

test("응답 유실 재요청은 원래 키·본문을 유지하고 새 코드는 별도 제출한다", async ({ page }) => {
  await mockPracticePage(page);
  const requests: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/submissions", async (route) => {
    requests.push({
      key: route.request().headers()["idempotency-key"],
      body: JSON.parse(route.request().postData() ?? "null"),
    });
    if (requests.length === 1) await route.abort("failed");
    else await route.fulfill({ status: 202, json: { success: true, data: receipt } });
  });
  await page.route("**/api/v1/submissions/2001", (route) =>
    route.fulfill({ json: { success: true, data: completed } }));
  await page.goto("/problems/1001");
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByRole("button", { name: "같은 제출 재요청" })).toBeVisible();
  expect(requests[0].body).toEqual({
    problemId: "1001", problemRevisionId: "2001", language: "java", sourceCode: "// java starter",
  });
  expect(requests[0].key).toBeTruthy();
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// changed code");
  await expect(page.locator(".view-lines")).toContainText("// changed code");
  await page.getByRole("button", { name: "같은 제출 재요청" }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  expect(requests[1]).toEqual(requests[0]);
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2].key).not.toBe(requests[0].key);
  expect(requests[2].body).toMatchObject({ sourceCode: "// changed code", problemId: "1001" });
});

test("조회 대기 종료·조회 장애 후 같은 제출만 재조회하고 새 접수는 만들지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  let posts = 0;
  let reads = 0;
  let mode: "queued" | "error" | "completed" = "queued";
  await page.route("**/api/v1/submissions", async (route) => {
    posts++;
    await route.fulfill({ status: 202, json: { success: true, data: receipt } });
  });
  await page.route("**/api/v1/submissions/2001", async (route) => {
    reads++;
    if (mode === "error") {
      await route.fulfill({ status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "조회 장애" } } });
    } else await route.fulfill({ json: { success: true, data: mode === "completed" ? completed : { submissionId: "2001", status: "QUEUED", effectiveResult: null, compileMessage: null } } });
  });
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.clock.install();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect.poll(() => reads).toBeGreaterThan(0);
  await page.clock.fastForward(60001);
  await expect(page.getByText("상태 조회를 잠시 멈췄습니다. 서버 채점은 계속될 수 있습니다.")).toBeVisible();
  const pausedReads = reads;
  await page.clock.fastForward(20000);
  expect(reads).toBe(pausedReads);
  mode = "error";
  await page.getByRole("button", { name: "채점 상태 다시 조회" }).click();
  await expect(page.getByText("조회 장애", { exact: true })).toBeVisible();
  mode = "completed";
  await page.getByRole("button", { name: "채점 상태 다시 조회" }).click();
  await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
  expect(posts).toBe(1);
});

test("같은 화면에서 제출 접수와 채점 완료를 제출 목록에 반영한다", async ({ page }) => {
  await mockPracticePage(page);
  let accepted = false;
  let done = false;
  let listReads = 0;
  await page.route("**/api/v1/me/submissions?**", (route) => {
    listReads++;
    return route.fulfill({ json: { success: true, data: {
      content: accepted ? [{ submissionId: "2001", problemId: "1001", problemRevisionId: "2001",
        language: "java", context: "PRACTICE", contextId: null, submittedAt: receipt.submittedAt,
        status: done ? "COMPLETED" : "QUEUED", verdict: done ? "AC" : null,
        maxTimeMs: done ? 1 : null, maxMemoryKb: done ? 1024 : null }] : [],
      page: 0, size: 20, totalElements: accepted ? 1 : 0, totalPages: accepted ? 1 : 0, hasNext: false,
    } } });
  });
  await page.route("**/api/v1/submissions", (route) => {
    accepted = true;
    return route.fulfill({ status: 202, json: { success: true, data: receipt } });
  });
  await page.route("**/api/v1/submissions/2001", (route) => route.fulfill({ json: { success: true,
    data: done ? completed : { ...completed, status: "QUEUED", effectiveResult: null },
  } }));
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "제출 내역", exact: true }).click();
  await expect(page.getByText("제출 기록이 없어요", { exact: true })).toBeVisible();
  const beforeSubmit = listReads;
  await page.clock.install();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  const row = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Java", exact: true }) });
  await expect(row).toBeVisible();
  await expect.poll(() => listReads).toBeGreaterThan(beforeSubmit);
  const beforeDone = listReads;
  done = true;
  await page.clock.fastForward(2100);
  await expect(row).toContainText("정답");
  await expect(row).toContainText("1ms");
  await expect.poll(() => listReads).toBeGreaterThan(beforeDone);
});

for (const retry of [false, true]) {
  test(`언어 전환 후 ${retry ? "같은 요청을 재시도해도" : "새 제출을 보내도"} 늦은 원래 응답이 완료 결과를 덮지 않는다`, async ({ page }) => {
    await mockPracticePage(page);
    await page.addInitScript(() => localStorage.setItem("resolve:editor", JSON.stringify({ language: "java", fontSize: 14 })));
    await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: {
      content: [{ language: "java", enabled: true }, { language: "python3", enabled: true }],
    } } }));
    let releaseFirst = () => {};
    const pendingFirst = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const requests: { key: string | undefined; body: unknown }[] = [];
    await page.route("**/api/v1/submissions", async (route) => {
      requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
      const first = requests.length === 1;
      const id = first || retry ? "2101" : "2102";
      if (first) await pendingFirst;
      await route.fulfill({ status: 202, json: { success: true, data: {
        ...receipt, submissionId: id, statusUrl: `/api/v1/submissions/${id}`,
      } } });
    });
    await page.route("**/api/v1/submissions/210*", (route) => route.fulfill({ json: { success: true, data: {
      ...completed, submissionId: route.request().url().split("/").at(-1),
    } } }));
    await page.goto("/problems/1001");
    await expect(page.locator(".view-lines")).toContainText("// java starter");
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect(page.getByRole("button", { name: "채점 중…", exact: true })).toBeDisabled();
    await page.getByLabel("코드 언어").selectOption("python3");
    await expect(page.locator(".view-lines")).toContainText("# python starter");
    await page.getByLabel("코드 언어").selectOption("java");
    await expect(page.locator(".view-lines")).toContainText("// java starter");
    await page.getByRole("button", { name: retry ? "같은 제출 재요청" : "현재 코드로 새 제출", exact: true }).click();
    await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
    expect(requests).toHaveLength(2);
    expect(requests[0].key).toBeTruthy();
    if (retry) expect(requests[1]).toEqual(requests[0]);
    else expect(requests[1].key).not.toBe(requests[0].key);
    const lateResponse = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/v1/submissions" && response.request().method() === "POST");
    releaseFirst();
    await (await lateResponse).finished();
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByText("AC 정답", { exact: true })).toBeVisible();
    expect(requests).toHaveLength(2);
  });
}
