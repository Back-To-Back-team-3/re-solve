import { expect, test } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";
import { readBrowserStorage, seedBrowserStorage } from "./storage-fixture";

test("언어를 왕복 전환해도 직접 입력·기대값을 유지한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, functionSpec: { ...problemDetail.functionSpec, parameters: [{ name: "a", type: { kind: "INT32" } }] },
  } } }));
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", displayName: "Java", enabled: true }, { language: "python3", displayName: "Python", enabled: true },
  ] } } }));
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 a", { exact: true }).fill("7");
  await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("7");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  const language = page.getByLabel("코드 언어", { exact: true });
  const original = await language.inputValue();
  await language.selectOption(original === "java" ? "python3" : "java");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 a", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("7");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await language.selectOption(original);
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 a", { exact: true })).toHaveValue("7");
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("7");
});

test("입력 중인 JSON과 기대값을 자동 저장하고 새로고침 후 같은 회차에 복구한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  const raw = "[9007199254740993,";
  await page.getByLabel("직접 입력 1 items", { exact: true }).fill(raw);
  await page.getByLabel("직접 입력 1 기대 출력", { exact: true }).fill("32");
  await expect.poll(() => page.evaluate(() => localStorage.getItem('re-solve:run-input:["7","practice","1001","2001"]')))
    .toContain(raw);
  await page.reload();
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue(raw);
  await expect(page.getByLabel("직접 입력 1 기대 출력", { exact: true })).toHaveValue("32");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await page.route("**/api/v1/problems/1001", (route) => route.fulfill({ json: { success: true, data: {
    ...problemDetail, problemRevisionId: "2002",
  } } }));
  await page.reload();
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveCount(0);
});

test("브라우저 저장 실패를 안내해도 입력 원문은 유지한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key.startsWith("re-solve:run-input:")) throw new DOMException("저장 공간 부족", "QuotaExceededError");
      original.call(this, key, value);
    };
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await page.getByRole("button", { name: "직접 입력 추가", exact: true }).click();
  await page.getByLabel("직접 입력 1 items", { exact: true }).fill("[1,");
  await expect(page.getByRole("alert").filter({ hasText: "브라우저 저장에 실패했습니다" })).toBeVisible();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue("[1,");
});

test("저장 실패 중 언어를 전환해도 저장소 복구 후 추가 편집 없이 원문을 저장한다", async ({ page }) => {
  await mockPracticePage(page);
  await page.route("**/api/v1/judge/languages", (route) => route.fulfill({ json: { success: true, data: { content: [
    { language: "java", displayName: "Java", enabled: true }, { language: "python3", displayName: "Python", enabled: true },
  ] } } }));
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    let blocked = false;
    window.addEventListener("test:block-run-storage", () => { blocked = true; });
    window.addEventListener("test:recover-run-storage", () => { blocked = false; });
    Storage.prototype.setItem = function (key, value) {
      if (blocked && key.startsWith("re-solve:run-input:")) throw new DOMException("저장 공간 부족", "QuotaExceededError");
      original.call(this, key, value);
    };
  });
  const key = 're-solve:run-input:["7","practice","1001","2001"]';
  await seedBrowserStorage(page, { [key]: JSON.stringify({ useSamples: true,
    customInputs: [{ arguments: { items: { kind: "TEXT", text: "[1]" } } }],
  }) });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue("[1]");
  await page.evaluate(() => window.dispatchEvent(new Event("test:block-run-storage")));
  const raw = "[9007199254740993,";
  await page.getByLabel("직접 입력 1 items", { exact: true }).fill(raw);
  await expect(page.getByRole("alert").filter({ hasText: "브라우저 저장에 실패했습니다" })).toBeVisible();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  const language = page.getByLabel("코드 언어", { exact: true });
  await language.selectOption(await language.inputValue() === "java" ? "python3" : "java");
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue(raw);
  await page.evaluate(() => window.dispatchEvent(new Event("test:recover-run-storage")));
  await expect.poll(() => readBrowserStorage(page, key), { timeout: 4000 }).toContain(raw);
  await expect(page.getByRole("alert").filter({ hasText: "브라우저 저장에 실패했습니다" })).toHaveCount(0);
  await page.reload();
  await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue(raw);
});

test("이전의 공개 예제 제외 선택을 복구해도 공개 예제를 항상 포함해 실행한다", async ({ page }) => {
  await mockPracticePage(page);
  await seedBrowserStorage(page, {
    're-solve:run-input:["7","practice","1001","2001"]': JSON.stringify({ useSamples: false,
      customInputs: [{ arguments: { items: { kind: "TEXT", text: "[1,2]" } } }],
    }),
  });
  let body: unknown;
  await page.route("**/api/v1/runs", async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({ json: { success: true, data: { compileMessage: null, results: [] } } });
  });
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "테스트 케이스 추가하기" }).click();
  await expect(page.getByRole("dialog").getByRole("checkbox")).toHaveCount(0);
  await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue("[1,2]");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect.poll(() => body).toMatchObject({ useSamples: true, customInputs: [{ argumentTexts: { items: "[1,2]" } }] });
});

for (const remove of [false, true]) {
  test(`로그아웃 직전 변경을 다음 주기 전에 저장한다 (${remove ? "모든 행 삭제" : "입력 수정"})`, async ({ page }) => {
    await page.clock.install();
    await mockPracticePage(page);
    await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ json: { success: true, data: null } }));
    const key = 're-solve:run-input:["7","practice","1001","2001"]';
    const initial = { useSamples: true, customInputs: [{ arguments: { items: { kind: "TEXT", text: "[1]" } } }] };
    await seedBrowserStorage(page, { [key]: JSON.stringify(initial) });
    await page.goto("/problems/1001");
    await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
    await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue("[1]");
    // 주기 저장을 멈춰 로그아웃 시 마지막 저장으로만 복구되는지 확인한다.
    await page.clock.pauseAt(new Date(Date.now() + 1000));
    const raw = "[9007199254740993,";
    if (remove) await page.getByRole("button", { name: "입력 1 삭제", exact: true }).click();
    else await page.getByLabel("직접 입력 1 items", { exact: true }).fill(raw);
    expect(JSON.parse((await readBrowserStorage(page, key)) ?? "null")).toEqual(initial);
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await page.getByRole("button", { name: "로그아웃", exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(JSON.parse((await readBrowserStorage(page, key)) ?? "null")).toEqual({ useSamples: true,
      customInputs: remove ? [] : [{ arguments: { items: { kind: "TEXT", text: raw } } }],
    });
    // 같은 회원의 재로그인 세션에서도 마지막 원문 또는 삭제 결과를 복원한다.
    await page.clock.resume();
    await page.reload();
    await page.goto("/problems/1001");
    await page.getByRole("button", { name: "테스트 케이스 추가하기", exact: true }).click();
    if (remove) await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveCount(0);
    else await expect(page.getByLabel("직접 입력 1 items", { exact: true })).toHaveValue(raw);
  });
}
