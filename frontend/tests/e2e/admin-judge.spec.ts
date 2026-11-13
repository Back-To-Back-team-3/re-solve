import { expect, test } from "@playwright/test";
import { mockAdminPage } from "./admin-authoring-fixture";
import { mockPracticePage } from "./practice-fixture";

const detail = {
  judgeJobId: "9101", submissionId: "2002", judgeAttempt: 2, status: "RUNNING", tryCount: 1,
  leaseUntil: "2026-11-13T10:01:00+09:00", nextRunAt: null, deadlineAt: "2026-11-13T10:10:00+09:00", lastError: "LEASE_EXPIRED",
  runs: [{ tryCount: 1, result: "SYSTEM_ERROR", errorMessage: "connect timeout", startedAt: "2026-11-13T10:00:00+09:00", finishedAt: null }],
  testResults: [{ testOrder: 1, tokenStatus: "UNKNOWN", judge0Token: null, verdict: null }],
};

test("채점 목록 필터·페이지를 전달하고 회원 문맥과 상세 실행 정보를 표시한다", async ({ page }) => {
  await mockAdminPage(page);
  const queries: URLSearchParams[] = [];
  await page.route("**/api/v1/admin/judge-jobs?**", (route) => {
    const query = new URL(route.request().url()).searchParams;
    queries.push(query);
    return route.fulfill({ json: { success: true, data: {
      content: [{ ...detail, memberId: "77", context: "EXAM", contextId: "401", problemRevisionId: "101", failureCode: null, createdAt: "2026-11-13T10:00:00+09:00", updatedAt: "2026-11-13T10:00:00+09:00" }],
      page: Number(query.get("page")), size: 20, totalElements: 21, totalPages: 2, hasNext: query.get("page") === "0",
    } } });
  });
  await page.route("**/api/v1/admin/judge-jobs/9101", (route) => route.fulfill({ json: { success: true, data: detail } }));
  await page.goto("/admin/judge-jobs");
  await expect(page.getByRole("cell", { name: "회원 77 EXAM · 401", exact: true })).toBeVisible();
  await page.getByLabel("작업 상태").selectOption("RUNNING");
  await page.getByLabel("제출 문맥").selectOption("EXAM");
  await page.getByLabel("문맥 ID", { exact: true }).fill("401");
  await page.getByLabel("실패 코드").fill("ENGINE_UNAVAILABLE");
  await page.getByLabel("정렬").selectOption("createdAt,asc");
  await page.getByRole("button", { name: "필터 적용" }).click();
  await expect.poll(() => queries.at(-1)?.get("contextId")).toBe("401");
  expect(queries.at(-1)?.get("status")).toBe("RUNNING");
  expect(queries.at(-1)?.get("context")).toBe("EXAM");
  expect(queries.at(-1)?.get("failureCode")).toBe("ENGINE_UNAVAILABLE");
  expect(queries.at(-1)?.get("sort")).toBe("createdAt,asc");
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.get("page")).toBe("1");
  await page.getByRole("link", { name: "작업 9101", exact: true }).click();
  await expect(page.getByText("connect timeout", { exact: true })).toBeVisible();
  await expect(page.getByText(/테스트 1: UNKNOWN/)).toBeVisible();
  await expect(page.getByText(/채점 2회차/)).toBeVisible();
});

test("복구 전송 실패는 같은 키·본문으로 재요청하고 완료 경합은 최신 상태로 갱신한다", async ({ page }) => {
  await mockAdminPage(page);
  let completed = false;
  const sent: { key: string | undefined; body: unknown }[] = [];
  await page.route("**/api/v1/admin/judge-jobs/9101", (route) => route.fulfill({ json: { success: true, data: { ...detail, status: completed ? "SUCCEEDED" : "RUNNING" } } }));
  await page.route("**/api/v1/admin/judge-jobs/9101/recoveries", async (route) => {
    sent.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
    if (sent.length === 1) return route.abort();
    if (sent.length === 2) return route.fulfill({ status: 202, json: { success: true, data: { judgeJobId: "9101", status: "RETRY_WAITING" } } });
    completed = true;
    return route.fulfill({ status: 409, json: { success: false, error: { code: "SUBMISSION_REJUDGE_NOT_ALLOWED", message: "이미 종결된 작업입니다." } } });
  });
  await page.goto("/admin/judge-jobs/9101");
  await page.getByLabel("복구 사유").fill("  실행기 복구  ");
  await page.getByRole("button", { name: "복구 요청", exact: true }).click();
  await expect(page.locator("main").getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "복구 요청", exact: true }).click();
  await expect(page.getByText(/복구 요청이 접수됐습니다/)).toBeVisible();
  expect(sent).toHaveLength(2);
  expect(sent[0].key).toBeTruthy();
  expect(sent[1]).toEqual(sent[0]);
  expect(sent[0].body).toEqual({ reason: "실행기 복구" });
  await page.getByLabel("복구 사유").fill("새 장애 확인");
  await page.getByRole("button", { name: "복구 요청", exact: true }).click();
  await expect(page.getByText("종결된 작업은 복구할 수 없습니다.")).toBeVisible();
  await expect(page.locator("main").getByRole("alert")).toHaveText("이미 종결된 작업입니다.");
  expect(sent[2].key).not.toBe(sent[1].key);
  await expect(page.getByRole("button", { name: "복구 요청", exact: true })).toHaveCount(0);
});

test("없는 작업은 오류로 표시하고 일반 회원은 관리 화면을 열지 못한다", async ({ page }) => {
  await mockAdminPage(page);
  await page.route("**/api/v1/admin/judge-jobs/999", (route) => route.fulfill({ status: 404, json: { success: false, error: { code: "SUBMISSION_NOT_FOUND", message: "작업을 찾을 수 없습니다." } } }));
  await page.goto("/admin/judge-jobs/999");
  await expect(page.getByText("작업을 찾을 수 없습니다.", { exact: true })).toBeVisible();
  await expect(page.getByLabel("복구 사유")).toHaveCount(0);
  await page.unroute("**/api/v1/members/me");
  await mockPracticePage(page);
  await page.reload();
  await expect(page).toHaveURL("/");
  await expect(page.getByRole("heading", { name: "채점 작업 관리" })).toHaveCount(0);
});
