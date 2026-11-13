import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

test("직접 입력을 검증하고 반환값·기대값·디버그 출력·성능을 구분한다", async ({ page }) => {
  await mockPracticePage(page);
  let body: unknown;
  await page.route("**/api/v1/runs", async (route) => {
    body = JSON.parse(route.request().postData() ?? "null");
    await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [{
      source: "CUSTOM", index: 0, verdict: null, actualReturnText: "3", timeMs: 2, memoryKb: 1024, stdout: "debug output",
    }] } } });
  });
  await page.goto("/problems/1001");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByText("실행 입력 설정", { exact: true }).click();
  await page.getByLabel("공개 예제 실행").uncheck();
  await page.getByRole("button", { name: "직접 입력 추가" }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("직접 입력 1의 items을 JSON 형식으로 입력해 주세요.")).toBeVisible();
  expect(body).toBeUndefined();
  await page.getByLabel("직접 입력 1 items").fill("[1,2]");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("debug output", { exact: true })).toBeVisible();
  await expect(page.getByText("기대값: 없음 (직접 입력)")).toBeVisible();
  expect(body).toMatchObject({ useSamples: false, customInputs: [{ argumentTexts: { items: "[1,2]" } }] });
});

test("제출 목록 페이지 이동과 상세 조회에서 숨김 원문을 렌더링하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/me/submissions?**", async (route) => {
    const current = Number(new URL(route.request().url()).searchParams.get("page"));
    await route.fulfill({ json: { success: true, data: {
      content: [{ submissionId: current === 0 ? "2000" : "2001", problemId: "1001", language: "java",
        context: "PRACTICE", contextId: null, status: "COMPLETED", verdict: "AC", maxTimeMs: 2,
        maxMemoryKb: 1024, submittedAt: "2026-11-13T00:00:00.000000+09:00" }],
      page: current, size: 20, totalElements: 21, totalPages: 2, hasNext: current === 0,
    } } });
  });
  await page.route("**/api/v1/submissions/2001**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path.endsWith("/source") ? { language: "java", sourceCode: "// my source" }
      : path.endsWith("/judge-attempts") ? { content: [{ judgeAttempt: 1, trigger: "INITIAL", status: "SUCCEEDED", verdict: "AC" }] }
      : path.endsWith("/test-results") ? { content: [
        { testOrder: 1, isSample: true, verdict: "AC", timeMs: 2, memoryKb: 1024, argumentTexts: { items: "[1,2]" }, expectedReturnText: "3", actualReturnText: "3" },
        { testOrder: 2, passed: true, argumentTexts: { secret: "hidden-secret" }, memoryKb: 99999 },
      ] } : { submissionId: "2001", problemId: "1001", problemRevisionId: "2001", language: "java", status: "FAILED", failureCode: "DEADLINE_EXCEEDED", effectiveResult: null };
    await route.fulfill({ json: { success: true, data } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "제출 내역", exact: true }).click();
  await expect(page.getByRole("button", { name: "이전", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await expect(page.getByRole("button", { name: "다음", exact: true })).toBeDisabled();
  await page.getByRole("link", { name: "결과 상세", exact: true }).click();
  await expect(page.getByText("// my source", { exact: true })).toBeVisible();
  await expect(page.getByText("숨김 · 통과", { exact: true })).toBeVisible();
  await expect(page.getByText("채점 시스템 실패: DEADLINE_EXCEEDED")).toBeVisible();
  await expect(page.locator("main")).not.toContainText("hidden-secret");
  await expect(page.locator("main")).not.toContainText("99999");
});

test("타인 제출 조회 거절 시 코드·결과를 표시하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/submissions/999**", (route) => route.fulfill({ status: 404,
    json: { success: false, error: { code: "SUBMISSION_NOT_FOUND", message: "제출을 찾을 수 없습니다." } } }));
  await page.goto("/submissions/999");
  await expect(page.getByText("제출을 찾을 수 없습니다.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "채점 회차" })).toHaveCount(0);
});
