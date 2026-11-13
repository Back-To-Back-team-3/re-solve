import { test, expect } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";
import { readBrowserStorage, seedBrowserStorage } from "./storage-fixture";

test("통과 언어와 활성 실행 환경의 교집합을 선택하고 예제 표·회차별 Draft를 표시한다", async ({ page }) => {
  await mockPracticePage(page);
  await seedBrowserStorage(page, {
    'resolve:draft:["7","practice","1001","java"]': JSON.stringify({ code: "// old revision", revisionId: "1999", savedAt: 1 }),
  }, { "draft-seeded": "true" });
  await page.goto("/problems/1001");
  const language = page.getByLabel("코드 언어");
  await expect(language).toHaveValue("java");
  await expect(language.locator("option")).toHaveCount(1);
  const examples = page.getByRole("table", { name: "입출력 예", exact: true });
  await expect(examples.getByRole("columnheader")).toHaveText(["items", "result"]);
  await expect(examples.getByRole("cell")).toHaveText(["[1,2]", "3"]);
  await expect(page.getByRole("heading", { name: "함수 명세", exact: true })).toHaveCount(0);
  const input = page.getByRole("textbox", { name: "코드 편집기" });
  await expect(input).toBeVisible();
  await expect(page.locator(".view-lines")).toContainText("// java starter");
  await expect(page.locator(".view-lines")).not.toContainText("old revision");
  await input.press("ControlOrMeta+A");
  await input.pressSequentially("// current draft");
  await expect.poll(async () => {
    const draft = await readBrowserStorage(page, 'resolve:draft:["7","practice","1001","java"]');
    return draft ? JSON.parse(draft).code : null;
  }).toBe("// current draft");
  // 같은 회차의 저장 값은 새 진입에서도 복원한다.
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("// current draft");
});

test("입출력 예는 매개변수 순서로 배열·객체 JSON 원문을 열마다 보존한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail,
    functionSpec: { ...problemDetail.functionSpec, parameters: [
      { name: "values", type: { kind: "ARRAY", elementType: { kind: "INT32" } } },
      { name: "options", type: { kind: "OBJECT", properties: { label: { kind: "STRING" } } } },
    ] },
    samples: [{ testAssetId: "1", argumentTexts: { options: '{"label":"a,b"}', values: "[1,2]" }, expectedReturnText: "3" }],
  } } }));
  await page.goto("/problems/1001");
  const examples = page.getByRole("table", { name: "입출력 예", exact: true });
  await expect(examples.getByRole("columnheader")).toHaveText(["values", "options", "result"]);
  await expect(examples.getByRole("cell")).toHaveText(["[1,2]", '{"label":"a,b"}', "3"]);
});

test("실행 가능한 언어가 없으면 에디터·실행·제출을 제공하지 않는다", async ({ page }) => {
  await mockPracticePage(page, false);
  await page.goto("/problems/1001");
  await expect(page.getByText("실행 가능한 언어가 없어요")).toBeVisible();
  await expect(page.getByRole("button", { name: "제출", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "실행", exact: true })).toHaveCount(0);
});


test("공개 예제 표가 있어도 별도로 작성한 예시 해설 Markdown을 표시한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, examples: "### 예제 풀이 과정\n\n첫 번째 원소와 두 번째 원소를 더합니다.",
  } } }));
  await page.goto("/problems/1001");
  await expect(page.getByRole("table", { name: "입출력 예", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "예제 풀이 과정", exact: true })).toBeVisible();
  await expect(page.getByText("첫 번째 원소와 두 번째 원소를 더합니다.", { exact: true })).toBeVisible();
});
