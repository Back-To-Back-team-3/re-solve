import { expect, test } from "@playwright/test";
import { mockAdminPage } from "./admin-authoring-fixture";

test("표의 함수 명세에서 고정 배열·큰 정수·반환 타입을 선택하고 테스트 값으로 전송한다", async ({ page }) => {
  await mockAdminPage(page);
  let request: unknown;
  await page.route("**/api/v1/admin/problems", async (route) => {
    request = route.request().postDataJSON(); await route.abort();
  });
  await page.goto("/admin/problems/new");
  await page.getByLabel("제목", { exact: true }).fill("선택형 함수 명세");
  await expect(page.getByLabel("함수 명세 (JSON)", { exact: true })).toHaveCount(0);
  const form = page.getByRole("region", { name: "함수 명세", exact: true });
  await expect(form.getByRole("columnheader", { name: "타입", exact: true })).toBeVisible();
  await page.getByLabel("매개변수 1 타입", { exact: true }).selectOption("ARRAY");
  await page.getByLabel("매개변수 1 배열 구성", { exact: true }).selectOption("positionTypes");
  await page.getByLabel("매개변수 1 항목 1 타입", { exact: true }).selectOption("INT64");
  await page.getByRole("button", { name: "매개변수 1 타입 항목 추가", exact: true }).click();
  await page.getByLabel("매개변수 1 항목 2 타입", { exact: true }).selectOption("STRING");
  await page.getByLabel("반환 타입", { exact: true }).selectOption("BOOLEAN");
  // 명세 변경으로 기존 테스트를 자동 변환하지 않으며 되돌리면 원래 값을 복구한다.
  await expect(page.getByText("함수 명세가 변경되었습니다.", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "새 함수 명세로 테스트 초기화", exact: true }).click();
  for (const index of [1, 2]) {
    await page.getByLabel(`테스트 ${index} 입력값 JSON`, { exact: true })
      .fill(`{"a":[${index === 1 ? "9223372036854775807" : "-9223372036854775808"},"문자 123"]}`);
    await page.getByLabel(`테스트 ${index} 기대 출력 JSON`, { exact: true }).fill("true");
  }
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect.poll(() => request).toBeTruthy();
  expect(request).toMatchObject({ functionSpec: {
    name: "solution", parameters: [{ name: "a", type: { kind: "ARRAY", positionTypes: [{ kind: "INT64" }, { kind: "STRING" }] } }],
    returnType: { kind: "BOOLEAN" },
  }, testCases: [
    { isSample: true, argumentTexts: { a: '[9223372036854775807,"문자 123"]' }, expectedReturnText: "true" },
    { isSample: false, argumentTexts: { a: '[-9223372036854775808,"문자 123"]' }, expectedReturnText: "true" },
  ] });
});

test("객체 필드·반복 배열과 매개변수 순서를 폼에서 구성하고 중복 이름을 막는다", async ({ page }) => {
  await mockAdminPage(page);
  let request: unknown;
  await page.route("**/api/v1/admin/problems", async (route) => {
    request = route.request().postDataJSON(); await route.abort();
  });
  await page.goto("/admin/problems/new");
  await page.getByLabel("제목", { exact: true }).fill("객체와 패턴");
  await page.getByLabel("매개변수 1 타입", { exact: true }).selectOption("OBJECT");
  await page.getByLabel("매개변수 1 새 필드 이름", { exact: true }).fill("enabled");
  await page.getByRole("button", { name: "매개변수 1 필드 추가", exact: true }).click();
  await page.getByLabel("매개변수 1.enabled 타입", { exact: true }).selectOption("BOOLEAN");
  await page.getByLabel("매개변수 1 새 필드 이름", { exact: true }).fill("enabled");
  await page.getByRole("button", { name: "매개변수 1 필드 추가", exact: true }).click();
  await expect(page.getByText("비어 있지 않은 중복 없는 필드 이름을 입력해 주세요.")).toBeVisible();
  await page.getByLabel("매개변수 1 새 필드 이름", { exact: true }).fill("");
  await page.getByRole("button", { name: "매개변수 추가", exact: true }).click();
  await page.getByLabel("매개변수 2 이름", { exact: true }).fill("items");
  await page.getByLabel("매개변수 2 타입", { exact: true }).selectOption("ARRAY");
  await page.getByLabel("매개변수 2 배열 구성", { exact: true }).selectOption("repeatTypes");
  await page.getByRole("button", { name: "매개변수 2 위로", exact: true }).click();
  await expect(page.getByLabel("매개변수 1 이름", { exact: true })).toHaveValue("items");
  await page.getByLabel("반환 타입", { exact: true }).selectOption("ARRAY");
  await page.getByRole("button", { name: "새 함수 명세로 테스트 초기화", exact: true }).click();
  for (const index of [1, 2]) {
    await page.getByLabel(`테스트 ${index} 입력값 JSON`, { exact: true }).fill('{"items":[],"a":{"enabled":false}}');
    await page.getByLabel(`테스트 ${index} 기대 출력 JSON`, { exact: true }).fill("[]");
  }
  await page.getByRole("button", { name: "문제 등록·재요청" }).click();
  await expect.poll(() => request).toBeTruthy();
  expect(request).toMatchObject({ functionSpec: { name: "solution", parameters: [
    { name: "items", type: { kind: "ARRAY", repeatTypes: [{ kind: "INT32" }] } },
    { name: "a", type: { kind: "OBJECT", properties: { enabled: { kind: "BOOLEAN" } } } },
  ], returnType: { kind: "ARRAY", elementType: { kind: "INT32" } } },
  testCases: [
    { isSample: true, argumentTexts: { items: "[]", a: '{"enabled":false}' }, expectedReturnText: "[]" },
    { isSample: false, argumentTexts: { items: "[]", a: '{"enabled":false}' }, expectedReturnText: "[]" },
  ] });
});
