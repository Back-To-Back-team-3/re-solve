import { test, expect, type Page } from "@playwright/test";
import { EXAM_ID, mockApi } from "./api-fixture";
import { readBrowserStorage } from "./storage-fixture";

test.use({ viewport: { width: 1004, height: 853 } });

const editor = (page: Page) => page.getByRole("textbox", { name: "코드 편집기" });
const code = (page: Page) => page.locator(".view-lines");

/** 저장 완료 문구는 화면에 표시하지 않아 저장소에 반영된 코드로 저장을 확인한다. */
async function storedCode(page: Page, key: string) {
  const raw = await readBrowserStorage(page, key);
  return raw ? (JSON.parse(raw) as { code?: string }).code : null;
}

async function edit(page: Page, text: string) {
  await editor(page).press("ControlOrMeta+A");
  await editor(page).pressSequentially(text);
  await expect(code(page)).toContainText(text);
}

test("좁은 PC 창에서도 모바일 전환 없이 문제 설명과 편집기를 함께 보여 주고 코드를 저장·실행한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/problems/1001");
  await expect(page.locator(".problem-panel")).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await expect(page.getByLabel("편집 방식")).toHaveCount(0);
  await expect(page.getByText(/1024px 이상의 데스크톱/)).toHaveCount(0);

  await page.getByLabel("코드 언어").selectOption("python3");
  await edit(page, "# narrow PC draft");
  await expect.poll(() => storedCode(page, 'resolve:draft:["7","practice","1001","python3"]')).toBe("# narrow PC draft");
  await page.getByLabel("코드 언어").selectOption("java");
  await page.getByLabel("코드 언어").selectOption("python3");
  await expect(code(page)).toContainText("# narrow PC draft");
  await page.reload();
  await expect(code(page)).toContainText("# narrow PC draft");

  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByRole("article", { name: "테스트 1" })).toContainText("공개 예제");

  // 창을 더 줄여도 두 열의 최소 폭을 지켜 가로 스크롤로 버티고 한 열로 접지 않는다.
  await page.setViewportSize({ width: 700, height: 853 });
  await expect(editor(page)).toBeVisible();
  const columns = await page.locator(".workspace").evaluate((element) =>
    getComputedStyle(element).gridTemplateColumns.split(" ").map(Number.parseFloat));
  expect(columns[0]).toBeGreaterThanOrEqual(350);
  expect(columns[1]).toBeGreaterThanOrEqual(380);
});

test("좁은 PC 창에서도 시험에 입실해 문제를 오가며 코드를 유지하고 응시를 종료해 결과로 이동한다", async ({ page }) => {
  await mockApi(page);
  await page.goto(`/exams/${EXAM_ID}`);
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await page.getByRole("button", { name: "응시 시작", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/room$`));

  await expect(page.locator(".exam-sidebar")).toBeVisible();
  await expect(page.locator(".problem-panel")).toBeVisible();
  await expect(editor(page)).toBeVisible();
  await edit(page, "// narrow PC exam");
  await expect.poll(() => storedCode(page, `re-solve:editor:draft:exam:${EXAM_ID}:1001`)).toBe("// narrow PC exam");
  await page.getByRole("button", { name: /문제 2/ }).click();
  await expect(page.getByRole("heading", { name: "고정 회차 배열 최댓값" })).toBeVisible();
  await page.getByRole("button", { name: /문제 1/ }).click();
  await expect(page.getByRole("heading", { name: "고정 회차 배열 합" })).toBeVisible();
  await expect(code(page)).toContainText("// narrow PC exam");

  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/result$`));
  await expect(page.getByRole("heading", { name: "도전의 기록" })).toBeVisible();
});

// 결함: 자동 저장(500ms) 전에 다른 문제로 옮기면 전환 시 저장이 일반 Draft 키에만 쓰이고 시험 복원은 시험 Draft만 읽어 코드가 사라진다.
test.fixme("시험 중 자동 저장 전에 다른 문제로 옮겼다 돌아와도 입력한 코드를 유지한다", async ({ page }) => {
  await mockApi(page);
  await page.goto(`/exams/${EXAM_ID}`);
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await page.getByRole("button", { name: "응시 시작", exact: true }).click();
  await expect(editor(page)).toBeVisible();
  await edit(page, "// switch before autosave");
  await page.getByRole("button", { name: /문제 2/ }).click();
  await expect(page.getByRole("heading", { name: "고정 회차 배열 최댓값" })).toBeVisible();
  await page.getByRole("button", { name: /문제 1/ }).click();
  await expect(code(page)).toContainText("// switch before autosave");
});
