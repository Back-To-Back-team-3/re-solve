import { test, expect, type Page } from "@playwright/test";
import { EXAM_ID, INSTANT_STUDY_ID, mockApi } from "./api-fixture";
import { readBrowserStorage } from "./storage-fixture";

const editor = (page: Page) => page.getByRole("textbox", { name: "코드 편집기" });

async function edit(page: Page, text: string) {
  await editor(page).press("ControlOrMeta+A");
  await editor(page).pressSequentially(text);
  await expect(page.locator(".view-lines")).toContainText(text);
}

/** 기획서 1.5 성공 기준의 전체 흐름(회원 → 문제 → 제출·채점 → 스터디 → 모의 코테)을 한 사용자로 끊김 없이 진행한다. */
test("로그인한 회원이 문제를 풀어 제출하고 스터디에 가입한 뒤 모의 코테를 응시해 결과를 확인한다", async ({ page }) => {
  test.setTimeout(180000);
  await mockApi(page);

  await test.step("로그인 상태로 시작한다", async () => {
    await page.goto("/");
    await expect(page.getByRole("link", { name: "테스터" })).toBeVisible();
  });

  await test.step("문제를 검색해 제출하고 채점 결과를 확인한다", async () => {
    await page.goto("/problems");
    await page.getByPlaceholder("문제 제목 또는 번호 검색").fill("배열 합");
    await page.getByRole("button", { name: "검색", exact: true }).click();
    await page.getByRole("link", { name: "고정 회차 배열 합" }).click();
    await expect(editor(page)).toBeVisible();
    await edit(page, "// solution");
    await page.getByRole("button", { name: "제출", exact: true }).click();
    const results = page.locator(".results");
    await expect(results.getByText("AC 정답", { exact: true })).toBeVisible();
    await expect(results).toContainText("5 / 5 통과");
  });

  await test.step("즉시 가입 스터디에 가입한다", async () => {
    await page.goto(`/studies/${INSTANT_STUDY_ID}`);
    await page.getByRole("button", { name: "가입", exact: true }).click();
    await expect(page.locator(".toast")).toHaveText("가입이 완료됐습니다.");
    await expect(page.getByRole("button", { name: "탈퇴", exact: true })).toBeVisible();
  });

  await test.step("스터디 모의 코테에 입실해 Draft를 저장하고 제출한 뒤 종료한다", async () => {
    await page.goto(`/exams/${EXAM_ID}`);
    await page.getByRole("button", { name: "참가 신청", exact: true }).click();
    await page.getByRole("button", { name: "응시 시작", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/room$`));
    await expect(editor(page)).toBeVisible();
    await edit(page, "// exam answer");
    // 시험 Draft 저장 성공은 화면에 표시하지 않아 저장소에 반영될 때까지 기다린다.
    await expect.poll(async () => {
      const raw = await readBrowserStorage(page, `re-solve:editor:draft:exam:${EXAM_ID}:1001`);
      return raw ? (JSON.parse(raw) as { code?: string }).code : null;
    }).toBe("// exam answer");
    await page.getByRole("button", { name: "제출", exact: true }).click();
    await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "응시 종료", exact: true }).click();
    await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
  });

  await test.step("시험 결과를 확인한다", async () => {
    await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/result$`));
    await expect(page.getByRole("heading", { name: "도전의 기록" })).toBeVisible();
    await expect(page.getByText("2 / 2문제 정답", { exact: true })).toBeVisible();
  });
});
