import { test, expect } from "@playwright/test";

test.use({ viewport: { width: 1004, height: 853 } });

test("narrow PC window keeps problem and Monaco visible without a mobile mode", async ({
  page,
}) => {
  await page.goto("/problems/1002");
  const input = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(page.locator(".problem-panel")).toBeVisible();
  await expect(input).toBeVisible();
  await expect(page.getByLabel("편집 방식")).toHaveCount(0);
  await expect(page.getByText(/1024px 이상의 데스크톱/)).toHaveCount(0);
  await input.press("ControlOrMeta+A");
  await input.pressSequentially("# narrow PC draft");
  await expect(page.getByText("저장 완료", { exact: true })).toBeVisible();
  await page.getByLabel("코드 언어").selectOption("java");
  await page.getByLabel("코드 언어").selectOption("python3");
  await expect(page.locator(".view-lines")).toContainText("# narrow PC draft");
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("# narrow PC draft");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("공개 예제 1", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "test-results/desktop-solve-1004.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "테마 전환", exact: true }).click();
  await page.screenshot({
    path: "test-results/desktop-solve-dark-1004.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 700, height: 853 });
  await expect(input).toBeVisible();
  const columns = await page
    .locator(".workspace")
    .evaluate((element) =>
      getComputedStyle(element)
        .gridTemplateColumns.split(" ")
        .map(Number.parseFloat),
    );
  expect(columns[0]).toBeGreaterThanOrEqual(350);
  expect(columns[1]).toBeGreaterThanOrEqual(380);
});

test("narrow PC window can start an exam, switch problems and finish", async ({
  page,
}) => {
  await page.goto("/exams/exam-1");
  await page
    .getByRole("button", { name: "데모 응시 시작", exact: true })
    .click();
  const input = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(page.locator(".exam-sidebar")).toBeVisible();
  await expect(page.locator(".problem-panel")).toBeVisible();
  await expect(input).toBeVisible();
  await input.press("ControlOrMeta+A");
  await input.pressSequentially("# narrow PC exam");
  await page.getByRole("button", { name: "문제 2 올바른 괄호" }).click();
  await page.getByRole("button", { name: "문제 1 중복 번호 찾기" }).click();
  await expect(page.locator(".view-lines")).toContainText("# narrow PC exam");
  await page.screenshot({
    path: "test-results/desktop-exam-1004.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page
    .getByRole("button", { name: "전체 제출 및 종료", exact: true })
    .click();
  await expect(page).toHaveURL(/exam-1\/result$/);
  await expect(
    page.getByRole("heading", { name: "도전의 기록" }),
  ).toBeVisible();
});
