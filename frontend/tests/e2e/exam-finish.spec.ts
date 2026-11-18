import { expect, test } from "@playwright/test";
import { mockExamRoom } from "./exam-fixture";

for (const expired of [false, true]) {
  test(`${expired ? "시간 만료" : "수동"} 종료 실패 시 코드를 유지하고 재시도 성공 뒤 이동한다`, async ({ page }) => {
    const { now, endsAt } = await mockExamRoom(page);
    let calls = 0;
    await page.route("**/api/v1/exams/42/finish", async (route) => {
      calls++;
      await route.fulfill(calls === 1 ? { status: 503, json: { success: false,
        error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "종료 처리 일시 실패" } } }
        : { json: { success: true, data: { status: "FINISHED", participantId: "1", personalEndsAt: endsAt,
          autoSubmittedAt: now, autoSubmissionIds: [] } } });
    });
    await page.goto("/exams/42/room");
    const editor = page.getByRole("textbox", { name: "코드 편집기" });
    await expect(editor).toBeVisible();
    await editor.pressSequentially("// preserve code");
    if (expired) {
      await page.clock.install();
      await page.clock.fastForward(3601000);
    } else {
      await page.getByRole("button", { name: "응시 종료", exact: true }).click();
      await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
    }
    await expect(page.getByText("종료 처리 일시 실패", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/exams\/42\/room$/);
    await expect(page.locator(".view-lines")).toContainText("// preserve code");
    expect(calls).toBe(1);
    if (expired) await expect(page.getByRole("button", { name: "실행", exact: true })).toBeDisabled();
    else await expect(page.getByRole("button", { name: "실행", exact: true })).toBeEnabled();
    await page.getByRole("button", { name: "응시 종료 재시도", exact: true }).click();
    await expect(page).toHaveURL(/\/exams\/42\/result$/);
    expect(calls).toBe(2);
  });
}

test("200 응답이어도 FINISHED가 아니면 결과로 이동하지 않는다", async ({ page }) => {
  await mockExamRoom(page);
  await page.route("**/api/v1/exams/42/finish", (route) => route.fulfill({ json: { success: true,
    data: { status: "STARTED" } } }));
  await page.goto("/exams/42/room");
  await expect(page.getByRole("textbox", { name: "코드 편집기" })).toBeVisible();
  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
  await expect(page.getByText(/응시 종료 상태를 확인하지 못했습니다/)).toBeVisible();
  await expect(page).toHaveURL(/\/room$/);
});
