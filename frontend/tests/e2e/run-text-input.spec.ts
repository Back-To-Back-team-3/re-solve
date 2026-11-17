import { expect, test } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

for (const width of [375, 983]) {
  test(`${width}px에서 모든 타입을 텍스트로 편집하고 중첩 큰 정수 원문을 전송한다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 852 });
    await mockPracticePage(page);
    const longName = "매우긴_배열_매개변수_이름_".repeat(6);
    await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
      ...problemDetail, samples: [], functionSpec: { name: "solution", parameters: [
        { name: longName, type: { kind: "ARRAY", elementType: { kind: "OBJECT", properties: {
          id: { kind: "INT64" }, enabled: { kind: "BOOLEAN" },
        } } } },
        { name: "message", type: { kind: "STRING" } },
        { name: "decimal", type: { kind: "FLOAT32" } },
        { name: "flag", type: { kind: "BOOLEAN" } },
        { name: "number", type: { kind: "INT32" } },
      ], returnType: { kind: "INT64" } },
    } } }));
    let body: { customInputs: { argumentTexts: Record<string, string> }[] } | undefined;
    await page.route("**/api/v1/runs", async (route) => {
      body = route.request().postDataJSON();
      await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [] } } });
    });
    await page.goto("/problems/1001");
    await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
    await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
    const input = page.getByLabel(`직접 입력 1 ${longName}`, { exact: true });
    const regularHeight = (await page.getByLabel("직접 입력 1 number", { exact: true }).boundingBox())?.height;
    expect((await input.boundingBox())?.height).toBe(regularHeight);
    expect((await page.getByLabel("직접 입력 1 message", { exact: true }).boundingBox())?.height).toBe(regularHeight);
    await expect(input).toHaveCSS("resize", "vertical");
    await expect(page.getByText("공개 예제는 항상 실행되며, 직접 입력을 추가할 수 있습니다.", { exact: true })).toHaveCount(0);
    const broken = '[{"id":9223372036854775808,"enabled":true}]';
    await input.fill(broken);
    await page.getByLabel("직접 입력 1 message", { exact: true }).fill("32");
    await page.getByLabel("직접 입력 1 decimal", { exact: true }).fill("3.14");
    await page.getByLabel("직접 입력 1 flag", { exact: true }).fill("true");
    await page.getByLabel("직접 입력 1 number", { exact: true }).fill("32");
    await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("9223372036854775807");
    await input.blur();
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(input).toHaveAttribute("aria-invalid", "true");
    await expect(input).toHaveValue(broken);
    await expect(page.getByRole("alert").first()).toContainText("[1].id");
    expect(body).toBeUndefined();
    const text = '[{"id":9223372036854775807,"enabled":true},{"id":-9223372036854775808,"enabled":false}]';
    await input.fill(text);
    await expect(input).toHaveAttribute("aria-invalid", "false");
    // 긴 이름·큰 값은 표 안에서만 가로 스크롤되고 입력 열의 최소 너비를 유지한다.
    expect(await page.getByRole("dialog").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    const bounds = await input.boundingBox();
    expect(bounds?.width).toBeGreaterThan(190);
    const scroll = page.getByRole("region", { name: "테스트 케이스 표 스크롤" });
    expect(await scroll.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
    await expect(page.getByRole("button", { name: "적용", exact: true })).toHaveCount(0);
    await expect(page.getByRole("dialog").getByRole("checkbox")).toHaveCount(0);
    await expect(page.getByRole("table", { name: "직접 입력 테스트 케이스" }).locator("tbody label")).toHaveCount(0);
    await expect(page.getByRole("table", { name: "직접 입력 테스트 케이스" }).getByRole("columnheader")).toHaveText([
      "번호", `${longName}배열 · JSON`, "message문자열", "decimal실수 (32비트)", "flag참·거짓", "number정수 (32비트)", "result (선택)정수 (64비트)", "관리",
    ]);
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByRole("button", { name: "실행", exact: true }).click();
    await expect.poll(() => body?.customInputs[0].argumentTexts[longName]).toBe(text);
    expect(body?.customInputs[0].argumentTexts).toMatchObject({ message: '"32"', decimal: "3.14", flag: "true", number: "32" });
    expect(JSON.stringify(body)).not.toContain("expected");
    await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
    await expect(input).toHaveValue(text);
    for (let index = 2; index <= 5; index++) await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
    await expect(page.getByRole("button", { name: "직접 입력 추가", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: /항목.*추가|항목.*삭제/ })).toHaveCount(0);
  });
}

test("검증을 건너뛰고 모달을 닫아도 잘못된 텍스트로 Run을 요청하지 않는다", async ({ page }) => {
  await mockPracticePage(page);
  let requests = 0;
  await page.route("**/api/v1/runs", async (route) => {
    requests++;
    await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [] } } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 items", { exact: true }).fill("[1,");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText(/직접 입력 1의 items: 유효한 JSON/)).toBeVisible();
  expect(requests).toBe(0);
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue("[1,");
});
