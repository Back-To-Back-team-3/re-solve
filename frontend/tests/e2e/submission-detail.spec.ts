import { expect, test } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

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
  const actions = page.locator(".editor-actions");
  const bounds = await actions.boundingBox();
  expect(bounds?.x).toBe(0);
  expect(bounds?.width).toBe(page.viewportSize()?.width);
  expect((bounds?.y ?? 0) + (bounds?.height ?? 0)).toBe(page.viewportSize()?.height);
  await expect(actions).not.toContainText("저장된 코드 복원 완료");
  await expect(actions).not.toContainText("초기 코드");
  const footer = page.locator("footer.footer");
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect(footer).toBeHidden();
  const workspaceBounds = await page.locator(".workspace").boundingBox();
  const panelBounds = await page.locator(".problem-panel").boundingBox();
  expect((workspaceBounds?.y ?? 0) + (workspaceBounds?.height ?? 0))
    .toBeCloseTo((panelBounds?.y ?? 0) + (panelBounds?.height ?? 0), 0);
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByLabel("공개 예제 실행").uncheck();
  await page.getByRole("button", { name: "직접 입력 추가" }).click();
  await page.getByRole("button", { name: "직접 입력 1 items 항목 추가", exact: true }).click();
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("직접 입력 1의 items[1]: 정수를 입력해 주세요.")).toBeVisible();
  expect(body).toBeUndefined();
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByLabel("직접 입력 1 items[1]", { exact: true }).fill("1");
  await page.getByRole("button", { name: "직접 입력 1 items 항목 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 items[2]", { exact: true }).fill("2");
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const resultCard = page.getByRole("article", { name: "테스트 1", exact: true });
  await expect(resultCard).toBeVisible();
  await expect(resultCard).toContainText("반환값: 3");
  await expect(resultCard).toContainText("실행 시간: 2ms · 메모리: 1024KiB");
  await expect(resultCard.getByRole("heading", { name: "디버그 출력" })).toBeVisible();
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
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(editor).toBeVisible();
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("// ongoing draft");
  await page.getByRole("link", { name: "제출 2001 코드 및 결과 보기", exact: true }).click();
  await expect(page.getByText("// my source", { exact: true })).toBeVisible();
  await expect(page.getByText("숨김 · 통과", { exact: true })).toBeVisible();
  await expect(page.getByText("채점 시스템 실패: DEADLINE_EXCEEDED")).toBeVisible();
  await expect(page.locator("main")).not.toContainText("hidden-secret");
  await expect(page.locator("main")).not.toContainText("99999");
  await expect(page.locator("main")).not.toContainText("// ongoing draft");
  await page.goBack();
  await expect(page.locator(".view-lines")).toContainText("// ongoing draft");
});

test("타인 제출 조회 거절 시 코드·결과를 표시하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/submissions/999**", (route) => route.fulfill({ status: 404,
    json: { success: false, error: { code: "SUBMISSION_NOT_FOUND", message: "제출을 찾을 수 없습니다." } } }));
  await page.goto("/submissions/999");
  await expect(page.getByText("제출을 찾을 수 없습니다.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "채점 회차" })).toHaveCount(0);
});

test("타입별 값 입력은 문자열·큰 정수·참거짓을 JSON 작성 없이 전송한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: { ...problemDetail, functionSpec: {
    name: "solution",
    parameters: [
      { name: "message", type: { kind: "STRING" } },
      { name: "number", type: { kind: "INT64" } },
      { name: "enabled", type: { kind: "BOOLEAN" } },
      { name: "options", type: { kind: "OBJECT", properties: { label: { kind: "STRING" } } } },
    ],
    returnType: { kind: "STRING" },
  }, samples: [] } } }));
  let body: unknown;
  await page.route("**/api/v1/runs", async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [] } } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByLabel("공개 예제 실행").uncheck();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await expect(page.getByRole("table", { name: "직접 입력 테스트 케이스" })
    .getByRole("columnheader")).toHaveText(["#", "message", "number", "enabled", "options", "기대 출력 (선택)", "관리"]);
  const messageBounds = await page.getByLabel("직접 입력 1 message", { exact: true }).boundingBox();
  const numberBounds = await page.getByLabel("직접 입력 1 number", { exact: true }).boundingBox();
  expect(messageBounds?.y).toBe(numberBounds?.y);
  await page.getByLabel("직접 입력 1 message", { exact: true }).fill('hello "world"\n다음 줄');
  await page.getByLabel("직접 입력 1 number", { exact: true }).fill("9007199254740993");
  await page.getByLabel("직접 입력 1 enabled", { exact: true }).selectOption("true");
  await page.getByLabel("직접 입력 1 options.label", { exact: true }).fill("a,b");
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect.poll(() => body).toMatchObject({ useSamples: false, customInputs: [{ argumentTexts: {
    message: JSON.stringify('hello "world"\n다음 줄'), number: "9007199254740993",
    enabled: "true", options: '{"label":"a,b"}',
  } }] });
});

test("사용자 기대값은 삭제한 행을 제외하고 실행 시점의 참고값으로 표시하며 API 판정과 분리한다", async ({ page }) => {
  await mockPracticePage(page);
  const requests: unknown[] = [];
  await page.route("**/api/v1/runs", async (route) => {
    requests.push(route.request().postDataJSON());
    await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [
      { source: "SAMPLE", verdict: "AC", actualReturnText: "3", expectedReturnText: "3", timeMs: 1, memoryKb: 1024, stdout: "" },
      { source: "CUSTOM", index: 0, verdict: null, actualReturnText: "3", timeMs: 1, memoryKb: 1024, stdout: "" },
    ] } } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 기대 출력 사용", { exact: true }).check();
  await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("99");
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 2 기대 출력 사용", { exact: true }).check();
  await page.getByLabel("직접 입력 2 기대 출력", { exact: true }).fill("42");
  await page.getByRole("button", { name: "입력 1 삭제", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("42");
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const custom = page.getByRole("article", { name: "테스트 2", exact: true });
  await expect(custom).toContainText("사용자 기대값: 42");
  await expect(custom).toContainText("실제 반환값: 3");
  await expect(custom).toContainText("실행 완료");
  await expect(custom).toContainText("자동 판정하지 않습니다");
  await expect(custom).not.toContainText("반환값이 기대값과 다릅니다");
  expect(requests[0]).toMatchObject({ useSamples: true, customInputs: [{ argumentTexts: { items: "[]" } }] });
  expect(JSON.stringify(requests[0])).not.toContain("expected");

  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("43");
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await expect(custom).toContainText("사용자 기대값: 42");
  await expect(custom).not.toContainText("사용자 기대값: 43");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByLabel("직접 입력 1 기대 출력 사용", { exact: true }).uncheck();
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(custom).toContainText("기대값: 없음 (직접 입력)");
  await expect(custom).not.toContainText("사용자 기대값:");
});
