import { test, expect } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";
import { seedBrowserStorage } from "./storage-fixture";

test("통과 언어와 활성 실행 환경의 교집합을 선택하고 중첩 타입·회차별 Draft를 보존한다", async ({ page }) => {
  await mockPracticePage(page);
  await seedBrowserStorage(page, {
    'resolve:draft:["7","practice","1001","java"]': JSON.stringify({ code: "// old revision", revisionId: "1999", savedAt: 1 }),
  }, { "draft-seeded": "true" });
  await page.goto("/problems/1001");
  const language = page.getByLabel("코드 언어");
  await expect(language).toHaveValue("java");
  await expect(language.locator("option")).toHaveCount(1);
  await expect(page.locator(".markdown")).toContainText("ARRAY<INT32>");
  const input = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(input).toBeVisible();
  await expect(page.locator(".view-lines")).toContainText("// java starter");
  await expect(page.locator(".view-lines")).not.toContainText("old revision");
  await input.press("ControlOrMeta+A");
  await input.pressSequentially("// current draft");
  await expect(page.getByText("저장 완료", { exact: true })).toBeVisible();
  // 같은 회차의 저장 값은 새 진입에서도 복원한다.
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("// current draft");
});

test("실행 가능한 언어가 없으면 에디터·실행·제출을 제공하지 않는다", async ({ page }) => {
  await mockPracticePage(page, false);
  await page.goto("/problems/1001");
  await expect(page.getByText("실행 가능한 언어가 없어요")).toBeVisible();
  await expect(page.getByRole("button", { name: "제출", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "실행", exact: true })).toHaveCount(0);
});
