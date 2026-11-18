import type { Page } from "@playwright/test";
import { mockPracticePage, problemDetail } from "./practice-fixture";

export async function mockExamRoom(page: Page, durationMs = 3600000) {
  await mockPracticePage(page);
  const now = new Date().toISOString();
  const endsAt = new Date(Date.now() + durationMs).toISOString();
  await page.route("**/api/v1/exams/42/problems", (route) => route.fulfill({ json: { success: true, data: {
    examId: "42", serverNow: now, personalEndsAt: endsAt,
    problems: [{ problemId: "1001", problemRevisionId: "9001", revisionNumber: 2,
      title: "시험 고정 회차", body: "시험 회차 본문", score: "100.00", displayOrder: 1,
      functionSpec: problemDetail.functionSpec, executionLimits: problemDetail.executionLimits,
      sampleTests: problemDetail.samples, imageIds: [] }],
  } } }));
  return { now, endsAt };
}
