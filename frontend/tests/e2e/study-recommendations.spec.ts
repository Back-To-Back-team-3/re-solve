import { expect, test, type Page } from "@playwright/test";
import { mockApi } from "./api-fixture";
import type { StudyRecommendation, StudyRecommendationsPage } from "@/services/types";

const recommendationsOf = (
  mode: StudyRecommendationsPage["mode"],
  isProfileRequired: boolean,
  content: StudyRecommendation[],
): StudyRecommendationsPage => ({
  mode, isProfileRequired, content,
  page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false,
});

// API 명세서 5.1.4: PERSONALIZED 근거는 정해진 문구를 정해진 순서로 담는다.
const personalized: StudyRecommendation = {
  studyId: "21", name: "개인화 추천 스터디", score: 87, memberCount: 5, capacity: 10, status: "RECRUITING",
  reasons: ["목표 일치", "태그 레벨 비슷함", "선호 언어 같음"],
};
// API 명세서 5.1.4: POPULAR는 점수 없이 최근 활동 근거 하나만 담는다.
const popular: StudyRecommendation = {
  studyId: "22", name: "인기 추천 스터디", score: null, memberCount: 3, capacity: 10, status: "RECRUITING",
  reasons: ["최근 활동이 많은 스터디"],
};

async function setup(page: Page, data: StudyRecommendationsPage) {
  await mockApi(page, { handle: async (route, path) => {
    if (path !== "/api/v1/studies/recommendations") return false;
    await route.fulfill({ json: { success: true, data } });
    return true;
  } });
}

test("개인화 추천을 받으면 나를 위한 추천 제목과 추천 근거를 모두 보여 준다", async ({ page }) => {
  await setup(page, recommendationsOf("PERSONALIZED", false, [personalized]));
  await page.goto("/studies");

  await expect(page.getByRole("heading", { name: "나를 위한 추천 스터디" })).toBeVisible();
  await expect(page.getByText("목표 일치, 태그 레벨 비슷함, 선호 언어 같음", { exact: true })).toBeVisible();
  await expect(page.getByText("더 정확한 추천을 받으려면 학습 프로필을 입력하세요.")).toHaveCount(0);
});

test("학습 프로필이 없으면 인기 스터디와 학습 프로필 입력 안내를 보여 준다", async ({ page }) => {
  await setup(page, recommendationsOf("POPULAR", true, [popular]));
  await page.goto("/studies");

  await expect(page.getByRole("heading", { name: "인기 스터디" })).toBeVisible();
  await expect(page.getByText("최근 활동이 많은 스터디", { exact: true })).toBeVisible();
  await expect(page.getByText("더 정확한 추천을 받으려면 학습 프로필을 입력하세요.")).toBeVisible();
});
