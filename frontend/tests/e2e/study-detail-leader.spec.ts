import { expect, test } from "@playwright/test";
import { mockApi } from "./api-fixture";
import type { StudyDetail } from "@/services/types";

for (const leader of [null, { memberId: "1", nickname: "테스트 리더" }]) {
  test(`스터디장 정보가 ${leader ? "있으면 닉네임" : "없으면 정보 없음"}을 표시하고 비구성원 권한을 유지한다`, async ({ page }) => {
    await mockApi(page, {
      handle: async (route, path) => {
        if (path !== "/api/v1/studies/2") return false;
        const data: StudyDetail = {
          studyId: "2", name: "상세 조회 스터디", description: "함께 공부합니다.",
          goal: "EMPLOYMENT", skillLevel: "LV1", preferredLanguage: "java",
          activeTimeSlot: "EVENING", capacity: 10, memberCount: 3,
          status: "RECRUITING", joinType: "APPROVAL", isAutoReopen: false,
          version: 1, leader, myMembership: null, createdAt: "2026-11-01T10:00:00+09:00",
        };
        await route.fulfill({ json: { success: true, data } });
        return true;
      },
    });
    await page.goto("/studies/2");
    await expect(page.getByRole("heading", { name: "상세 조회 스터디" })).toBeVisible();
    await expect(page.locator("p").filter({ hasText: `스터디장 ${leader?.nickname ?? "정보 없음"}` })).toHaveCount(2);
    await expect(page.getByRole("button", { name: "신청하기", exact: true })).toBeVisible();
    for (const tab of ["구성원", "주간 문제집"]) {
      await page.getByRole("button", { name: tab, exact: true }).click();
      await expect(page.getByText("구성원만 이용할 수 있습니다.", { exact: true })).toBeVisible();
    }
    await expect(page.getByText("화면을 표시하지 못했습니다.")).toHaveCount(0);
  });
}
