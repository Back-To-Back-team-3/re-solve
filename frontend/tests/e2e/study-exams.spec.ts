import { expect, test } from "@playwright/test";
import { mockApi } from "./api-fixture";

for (const role of ["LEADER", "MANAGER", "MEMBER", null]) {
  test(`스터디 시험 목록과 생성 권한 ${role ?? "탈퇴한 활성 참가자"}`, async ({ page }) => {
    let reads = 0;
    await mockApi(page, { handle: async (route, path) => {
      if (path === "/api/v1/studies/2") {
        await route.fulfill({ json: { success: true, data: {
          studyId: "2", name: "시험 스터디", description: "소개", goal: "EMPLOYMENT", skillLevel: "LV1",
          preferredLanguage: "java", activeTimeSlot: "EVENING", capacity: 10, memberCount: 3,
          status: "ACTIVE", joinType: "APPROVAL", isAutoReopen: false, version: 1,
          leader: { memberId: "7", nickname: "리더" },
          myMembership: role ? { membershipId: "1", status: "APPROVED", role } : null,
        } } }); return true;
      }
      if (path === "/api/v1/studies/2/exams") {
        reads++;
        await route.fulfill({ json: { success: true, data: { content: [{
          examId: "55", title: "이번 주 시험", status: "SCHEDULED", startsAt: new Date().toISOString(),
          problemCount: 2, participantCount: 1,
        }], page: 0, size: 20, totalPages: 1, totalElements: 1, hasNext: false } } }); return true;
      }
      return false;
    } });
    await page.goto("/studies/2");
    await page.getByRole("button", { name: "모의 코딩테스트", exact: true }).click();
    await expect(page.getByRole("heading", { name: "이번 주 시험" })).toBeVisible();
    const create = page.getByRole("link", { name: "시험 만들기" });
    expect(reads).toBeGreaterThan(0);
    if (role === "MEMBER" || !role) await expect(create).toHaveCount(0);
    else await expect(create).toHaveAttribute("href", "/exams/new?studyId=2");
    await expect(page.getByRole("link", { name: "자세히 보기" })).toHaveAttribute("href", "/exams/55");
  });
}

test("시험 참가 권한이 없는 비구성원은 서버의 403을 표시하고 생성 링크를 숨긴다", async ({ page }) => {
  await mockApi(page, { handle: async (route, path) => {
    if (path === "/api/v1/studies/2") {
      await route.fulfill({ json: { success: true, data: {
        studyId: "2", name: "시험 스터디", description: "소개", goal: "EMPLOYMENT", skillLevel: "LV1",
        preferredLanguage: "java", activeTimeSlot: "EVENING", capacity: 10, memberCount: 3,
        status: "ACTIVE", joinType: "APPROVAL", isAutoReopen: false, version: 1,
        leader: { memberId: "1", nickname: "리더" }, myMembership: null,
      } } }); return true;
    }
    if (path === "/api/v1/studies/2/exams") {
      await route.fulfill({ status: 403, json: { success: false,
        error: { code: "AUTH_ACCESS_DENIED", message: "시험 목록 접근 권한이 없습니다." } } }); return true;
    }
    return false;
  } });
  await page.goto("/studies/2");
  await page.getByRole("button", { name: "모의 코딩테스트", exact: true }).click();
  await expect(page.getByText("시험 목록 접근 권한이 없습니다.", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "시험 만들기" })).toHaveCount(0);
  await page.getByRole("button", { name: "소개", exact: true }).click();
  await expect(page.getByRole("heading", { name: "시험 스터디", exact: true })).toBeVisible();
});
