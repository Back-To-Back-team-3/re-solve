import { expect, test, type Page } from "@playwright/test";
import { mockApi } from "./api-fixture";
import type { ServerPage, StudyDetail, StudyMemberItem } from "@/services/types";

const study: StudyDetail = {
  studyId: "2", name: "승인 심사 스터디", description: "함께 공부합니다.",
  goal: "EMPLOYMENT", skillLevel: "LV2", preferredLanguage: "java", activeTimeSlot: "EVENING",
  capacity: 10, memberCount: 1, status: "RECRUITING", joinType: "APPROVAL", isAutoReopen: false,
  version: 1, leader: { memberId: "7", nickname: "테스터" },
  myMembership: { membershipId: "1", role: "LEADER", status: "APPROVED" }, createdAt: "2026-11-01T00:00:00Z",
};
const pageOf = <T>(content: T[]): ServerPage<T> => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false,
});
const leader: StudyMemberItem = {
  membershipId: "1", memberId: "7", nickname: "테스터", role: "LEADER", status: "APPROVED",
  joinedAt: "2026-11-01T00:00:00Z",
};
// API 명세서 5.2.4: 태그별 레벨은 태그 ID를 키로 담는다. "9"는 태그 목록(카탈로그 1·2)에 없는 태그다.
const applicant: StudyMemberItem = {
  membershipId: "2", memberId: "8", nickname: "신청자", role: "MEMBER", status: "PENDING",
  joinedAt: null, appliedAt: "2026-11-13T00:00:00Z", expiresAt: "2026-11-20T00:00:00Z",
  learningProfile: { goal: "EMPLOYMENT", tagLevels: { "1": "LV2", "9": "LV1" }, preferredLanguages: ["java"] },
};

async function setup(page: Page) {
  await mockApi(page, { handle: async (route, path) => {
    const ok = (data: unknown) => route.fulfill({ json: { success: true, data } });
    if (path === "/api/v1/studies/2") { await ok(study); return true; }
    if (path === "/api/v1/studies/2/memberships") {
      const status = new URL(route.request().url()).searchParams.get("status");
      await ok(pageOf(status === "PENDING" ? [applicant] : [leader]));
      return true;
    }
    return false;
  } });
}

test("스터디장이 가입 신청을 보면 신청자의 태그 레벨을 태그 이름으로 보여 준다", async ({ page }) => {
  await setup(page);
  await page.goto("/studies/2");
  await page.getByRole("button", { name: "구성원", exact: true }).click();

  await expect(page.getByRole("heading", { name: "가입 신청 (1)" })).toBeVisible();
  await expect(page.getByText("취업 준비 · 배열 LV2 · 9 LV1", { exact: true })).toBeVisible();
});
