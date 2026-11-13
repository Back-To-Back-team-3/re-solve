import type { Page } from "@playwright/test";
import { mockPracticePage } from "./practice-fixture";

export const adminDetail = {
  problemId: "1001", problemRevisionId: "2001", revisionNumber: 1, rowVersion: 3, authorId: "7",
  scope: "GENERAL", visibility: "PRIVATE", currentProblemRevisionId: null, supportedLanguages: [],
  title: "관리자 문제", description: "문제 설명", constraints: "제한", examples: "예시", difficulty: 1, tagIds: [],
  functionSpec: { name: "solution", parameters: [{ name: "a", type: { kind: "INT32" } }], returnType: { kind: "INT32" } },
  executionLimits: null,
  testCases: [
    { isSample: true, argumentTexts: { a: "1" }, expectedReturnText: "1", testAssetId: "10" },
    { isSample: false, argumentTexts: { a: "2" }, expectedReturnText: "2", testAssetId: "11" },
  ], referenceSolutions: [], languageResults: [],
};
export async function mockAdminPage(page: Page) {
  await mockPracticePage(page);
  await page.route("**/api/v1/members/me", (route) => route.fulfill({ json: { success: true, data: {
    memberId: "7", nickname: "관리자", role: "ADMIN", status: "ACTIVE", githubLogin: "admin",
    profileImageUrl: null, email: null, hasLearningProfile: true, createdAt: "2026-11-13T00:00:00+09:00",
  } } }));
  await page.route("**/api/v1/admin/problems/1001**", (route) => {
    const path = new URL(route.request().url()).pathname;
    return route.fulfill({ json: { success: true, data: path.endsWith("/revisions") ? {
      content: [{ problemRevisionId: "2001", revisionNumber: 1, title: "관리자 문제", publishedAt: "2026-11-13T00:00:00+09:00", currentProblemRevisionId: null, supportedLanguages: [] }],
      page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false,
    } : adminDetail } });
  });
}
