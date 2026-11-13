import { expect, test } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

test("언어를 왕복 전환해도 직접 입력·기대값·공개 예제 선택을 유지한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, functionSpec: { ...problemDetail.functionSpec, parameters: [{ name: "a", type: { kind: "INT32" } }] },
  } } }));
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", displayName: "Java", enabled: true }, { language: "python3", displayName: "Python", enabled: true },
  ] } } }));
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await page.getByLabel("공개 예제 실행", { exact: true }).uncheck();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 a", { exact: true }).fill("7");
  await page.getByLabel("직접 입력 1 기대 출력 사용", { exact: true }).check();
  await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("7");
  await page.getByRole("button", { name: "적용", exact: true }).click();
  const language = page.getByLabel("코드 언어", { exact: true });
  const original = await language.inputValue();
  await language.selectOption(original === "java" ? "python3" : "java");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 a", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("공개 예제 실행", { exact: true })).not.toBeChecked();
  await page.getByRole("button", { name: "적용", exact: true }).click();
  await language.selectOption(original);
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 a", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("공개 예제 실행", { exact: true })).not.toBeChecked();
});
