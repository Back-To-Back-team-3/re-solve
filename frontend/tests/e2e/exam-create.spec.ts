import { expect, test } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

for (const edited of [false, true]) {
  test(`문제집 없는 스터디에서 공개 문제를 선택해 ${edited ? "수정" : "기본"} 배점으로 생성한다`, async ({ page }) => {
    await mockPracticePage(page);
    await page.route("**/api/v1/studies/2", (route) => route.fulfill({ json: { success: true, data: {
      studyId: "2", name: "새 스터디", status: "ACTIVE", myMembership: { role: "LEADER", status: "APPROVED" },
    } } }));
    let assignmentReads = 0;
    await page.route("**/api/v1/studies/2/assignments**", (route) => { assignmentReads++; return route.fulfill({ json: { success: true, data: { content: [] } } }); });
    let body: { problems: { problemId: string; score: string; displayOrder: number }[] } | undefined;
    await page.route("**/api/v1/studies/2/exams", (route) => {
      body = route.request().postDataJSON();
      return route.fulfill({ status: 201, json: { success: true, data: { examId: "55" } } });
    });
    await page.goto("/exams/new?studyId=2");
    await page.getByLabel("시험 이름", { exact: true }).fill("문제집 없는 시험");
    const start = new Date(Date.now() + 3600000);
    const localDate = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    await page.getByLabel("시작 일시").fill(localDate(start));
    await page.getByLabel("종료 일시").fill(localDate(new Date(start.getTime() + 3600000)));
    await page.getByLabel("문제 검색", { exact: true }).fill("1001");
    await page.getByRole("button", { name: "검색", exact: true }).click();
    const add = page.getByRole("button", { name: / 선택$/ }).first();
    await expect(add).toBeVisible();
    await add.click();
    const scores = page.getByRole("table", { name: "선택한 시험 문제" });
    await expect(scores.getByRole("textbox")).toHaveValue("100.00");
    if (edited) {
      await scores.getByRole("button", { name: /제거$/ }).click();
      await expect(scores.getByRole("textbox")).toHaveCount(0);
      await add.click();
      await scores.getByRole("textbox").fill("32.5");
    }
    await page.getByRole("button", { name: "시험 생성", exact: true }).click();
    await expect(page).toHaveURL(/\/exams\/55$/);
    expect(body?.problems).toEqual([{ problemId: "1001", score: edited ? "32.50" : "100.00", displayOrder: 1 }]);
    expect(assignmentReads).toBe(0);
  });
}
