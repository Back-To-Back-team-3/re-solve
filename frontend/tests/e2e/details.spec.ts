import { test, expect } from "@playwright/test";
test("questions, notes, profile, settings and all study tabs persist", async ({
  page,
}) => {
  await page.goto("/problems/1001");
  await page.getByRole("button", { name: "질문", exact: true }).click();
  await page
    .getByLabel("질문 작성")
    .fill("중복이 없을 때 반환값을 확인하고 싶어요.");
  await page.getByRole("button", { name: "글 등록" }).click();
  await expect(
    page.getByText("중복이 없을 때 반환값을 확인하고 싶어요.", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "풀이 노트", exact: true }).click();
  await page
    .getByLabel("나만의 풀이 노트")
    .fill("해시로 빈도수를 세고 최소 중복값을 고른다.");
  await page.getByRole("button", { name: "노트 저장" }).click();
  await expect(
    page.getByText("풀이 노트를 저장했습니다.", { exact: true }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "풀이 노트", exact: true }).click();
  await expect(page.getByLabel("나만의 풀이 노트")).toHaveValue(
    "해시로 빈도수를 세고 최소 중복값을 고른다.",
  );
  await page.goto("/mypage");
  await page.getByLabel("닉네임").fill("성장하는 개발자");
  await page.getByRole("button", { name: "프로필 저장" }).click();
  await expect(
    page.getByRole("heading", { name: "성장하는 개발자님의 마이페이지" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "설정", exact: true }).click();
  await page.getByLabel("에디터 글자 크기").selectOption("18");
  await page
    .getByRole("combobox", { name: "테마", exact: true })
    .selectOption("dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.goto("/studies/study-1");
  await page.getByRole("button", { name: "구성원", exact: true }).click();
  await expect(page.getByText("스터디장", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "주간 문제집", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "중복 번호 찾기", exact: true }),
  ).toBeVisible();
});
test("exam room visual states in both desktop themes", async ({ page }) => {
  await page.goto("/exams/exam-1");
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "참가 취소", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "참가 취소", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "참가 신청", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "데모 응시 시작", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "코드 편집기" }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/room-light-1440.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "테마 전환", exact: true }).click();
  await page.screenshot({
    path: "test-results/room-dark-1440.png",
    fullPage: true,
  });
});
test("signup returns to destination and stores no password", async ({
  page,
}) => {
  await page.goto("/signup?returnTo=%2Fstudies");
  await expect(page.locator("main")).toHaveAttribute("data-ready", "true");
  await page.getByLabel("닉네임").fill("새로운 동료");
  await page.getByLabel("이메일").fill("new@resolve.demo");
  await page.getByLabel("비밀번호").fill("throw-away-password");
  await page.getByRole("button", { name: "데모 회원가입" }).click();
  await expect(page).toHaveURL("/studies");
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "throw-away-password",
  );
  await expect(
    page.getByRole("link", { name: "새로운 동료", exact: true }),
  ).toBeVisible();
});
