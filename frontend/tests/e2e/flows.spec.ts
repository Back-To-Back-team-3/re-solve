import { test, expect, type Page } from "@playwright/test";
async function ready(page: Page, path: string) {
  await page.goto(path);
  await expect(page.getByText("LOADING...", { exact: true })).toHaveCount(0);
}
async function edit(page: Page, text: string) {
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially(text);
  await expect(page.locator(".view-lines")).toContainText(text);
  await expect(page.getByText("저장 완료", { exact: true })).toBeVisible();
}
async function scenario(page: Page, value: string) {
  await page.getByRole("button", { name: "데모 설정", exact: true }).click();
  await page.getByLabel("다음 채점 시나리오").selectOption(value);
  await page.getByRole("button", { name: "시나리오 적용" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
}
test("search, code isolation, refresh recovery, public and hidden judgement", async ({
  page,
}) => {
  await ready(page, "/problems");
  await page.getByPlaceholder("문제 제목 또는 번호 검색").fill("중복");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page).toHaveURL(/keyword=/);
  await page.getByRole("link", { name: "중복 번호 찾기", exact: true }).click();
  await edit(page, "# Python saved draft");
  await page.getByLabel("코드 언어").selectOption("java");
  await edit(page, "// Java saved draft");
  await page.getByLabel("코드 언어").selectOption("python3");
  await expect(page.locator(".view-lines")).toContainText(
    "# Python saved draft",
  );
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText(
    "# Python saved draft",
  );
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByText("공개 예제 1", { exact: true })).toBeVisible();
  await scenario(page, "WA");
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("숨김 테스트 5", { exact: true })).toBeVisible();
  await expect(page.locator(".results")).toContainText("오답");
  await expect(page.locator(".results")).not.toContainText("기대값");
  await ready(page, "/problems/1002");
  await edit(page, "# Another problem");
  await ready(page, "/problems/1001");
  await expect(page.locator(".view-lines")).toContainText(
    "# Python saved draft",
  );
  await scenario(page, "FAILED");
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(
    page.getByText("모의 채점 시스템 오류입니다.", { exact: false }),
  ).toBeVisible();
});
test("study creation, application cancellation and board comments", async ({
  page,
}) => {
  await ready(page, "/studies/new");
  await page.getByLabel("스터디 이름").fill("검증용 스터디");
  await page
    .getByLabel("스터디 소개")
    .fill("함께 알고리즘 문제를 매일 연습하는 스터디입니다.");
  await page.getByLabel("학습 목표").fill("매일 한 문제");
  await page.getByLabel("진행 시간").fill("수요일 20:00");
  await page.getByRole("button", { name: "스터디 생성" }).click();
  await expect(
    page.getByRole("heading", { name: "검증용 스터디" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "게시판", exact: true }).click();
  await page.getByLabel("새 글 작성").fill("첫 번째 모임 공지입니다.");
  await page.getByRole("button", { name: "글 등록" }).click();
  await expect(
    page.getByText("첫 번째 모임 공지입니다.", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "댓글 쓰기" }).click();
  await page.getByLabel("댓글", { exact: true }).fill("확인했습니다.");
  await page.getByRole("button", { name: "등록", exact: true }).click();
  await expect(page.getByText("확인했습니다.", { exact: true })).toBeVisible();
  await ready(page, "/studies");
  await expect(
    page.getByRole("heading", { name: "검증용 스터디" }),
  ).toBeVisible();
  await ready(page, "/studies/study-2");
  await page.getByRole("button", { name: "가입 신청", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "가입 신청 취소", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "가입 신청 취소", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "가입 신청", exact: true }),
  ).toBeVisible();
});
test("exam creation, entry, draft switching, finish and expiry", async ({
  page,
}) => {
  await ready(page, "/exams/new");
  await page.getByLabel("시험 이름").fill("검증용 모의 코테");
  await page
    .getByLabel("시험 소개")
    .fill("문제 전환과 제출 흐름을 검증하는 모의 시험입니다.");
  await page.getByLabel("시작 일시").fill("2026-10-07T18:00");
  await page.getByLabel("1002. 올바른 괄호").check();
  await page.getByRole("button", { name: "시험 생성", exact: true }).click();
  await page
    .getByRole("button", { name: "데모 응시 시작", exact: true })
    .click();
  await expect(page).toHaveURL(/\/room$/);
  await edit(page, "# exam first draft");
  await page.getByRole("button", { name: "문제 2 올바른 괄호" }).click();
  await edit(page, "# exam second draft");
  await page.getByRole("button", { name: "문제 1 중복 번호 찾기" }).click();
  await expect(page.locator(".view-lines")).toContainText("# exam first draft");
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("숨김 테스트 5", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page.getByRole("button", { name: "전체 제출 및 종료" }).click();
  await expect(page).toHaveURL(/\/result$/);
  await expect(
    page.getByRole("heading", { name: "도전의 기록" }),
  ).toBeVisible();
  await expect(page.getByText("2 / 2문제 정답", { exact: true })).toBeVisible();
  await ready(page, "/exams/exam-2");
  await page
    .getByRole("button", { name: "데모 응시 시작", exact: true })
    .click();
  await page.getByRole("button", { name: "데모: 시간 만료" }).click();
  await expect(page).toHaveURL(/exam-2\/result$/);
});
test("auth return path, empty state, invalid ID, and read failure recovery", async ({
  page,
}) => {
  await ready(page, "/studies/study-2");
  await page.getByRole("button", { name: "로그아웃", exact: true }).click();
  await page.getByRole("button", { name: "가입 신청", exact: true }).click();
  await expect(page).toHaveURL(/login\?returnTo=/);
  await page.getByLabel("이메일").fill("tester@resolve.demo");
  await page.getByLabel("비밀번호").fill("not-stored");
  await page.getByRole("button", { name: "데모 로그인", exact: true }).click();
  await expect(page).toHaveURL(/studies\/study-2$/);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(
    "not-stored",
  );
  await ready(page, "/problems?keyword=없는문제");
  await expect(
    page.getByRole("heading", { name: "검색 결과가 없어요" }),
  ).toBeVisible();
  await ready(page, "/problems/missing");
  await expect(
    page.getByRole("heading", { name: "문제를 찾을 수 없어요" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "데모 설정", exact: true }).click();
  await page
    .getByRole("button", { name: "조회 오류 재현", exact: true })
    .click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await ready(page, "/dashboard");
  await expect(
    page.getByRole("heading", { name: "불러오지 못했어요" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "데모 설정", exact: true }).click();
  await page
    .getByRole("button", { name: "조회 오류 해제", exact: true })
    .click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "오늘도 한 걸음, 더 성장해요" }),
  ).toBeVisible();
});
test("draft storage failure keeps editable code and announces recovery action", async ({
  page,
}) => {
  await ready(page, "/problems/1001");
  await expect(
    page.getByRole("textbox", { name: "코드 편집기" }),
  ).toBeVisible();
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k, v) {
      if (k.startsWith("resolve:draft:"))
        throw new DOMException("quota", "QuotaExceededError");
      return original.call(this, k, v);
    };
  });
  const editor = page.getByRole("textbox", { name: "코드 편집기" });
  await editor.press("ControlOrMeta+A");
  await editor.pressSequentially("# preserve on failure");
  await expect(
    page.getByText("저장 실패 · 코드를 복사해 보관해 주세요."),
  ).toBeVisible();
  await expect(page.locator(".view-lines")).toContainText(
    "# preserve on failure",
  );
});
test("modal keyboard focus, persisted dark theme, pet hatch", async ({
  page,
}) => {
  await ready(page, "/");
  await page.getByRole("button", { name: "알 부화하기" }).first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "부화시키기", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "테마 전환", exact: true }).click();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "데모 설정", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "닫기", exact: true }),
  ).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "데모 설정", exact: true }),
  ).toBeFocused();
});
