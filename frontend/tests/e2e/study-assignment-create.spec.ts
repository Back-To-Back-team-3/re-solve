import { expect, test, type Page } from "@playwright/test";
import { mockApi } from "./api-fixture";
import type { AssignmentDetail, CreateAssignmentRequest, ProblemListItem, StudyDetail } from "@/services/types";

const baseStudy: StudyDetail = {
  studyId: "2", name: "문제집 스터디", description: "함께 공부합니다.",
  goal: "STUDY", skillLevel: "LV2", preferredLanguage: "java", activeTimeSlot: "EVENING",
  capacity: 10, memberCount: 2, status: "ACTIVE", joinType: "APPROVAL", isAutoReopen: false,
  version: 1, leader: { memberId: "7", nickname: "테스터" },
  myMembership: { membershipId: "1", role: "LEADER", status: "APPROVED" }, createdAt: "2026-11-01T00:00:00Z",
};
const problems: ProblemListItem[] = Array.from({ length: 21 }, (_, index) => ({
  problemId: String(index + 1), problemRevisionId: String(index + 101), revisionNumber: 1,
  title: `연습 문제 ${index + 1}`, difficulty: index < 3 ? index + 1 : 1,
  tags: [{ tagId: index % 2 ? "2" : "1", name: index % 2 ? "정렬" : "해시" }],
  publishedAt: "2026-11-01T00:00:00Z", solvedStatus: "UNSOLVED", supportedLanguages: ["java"],
}));
const pageOf = <T>(content: T[]) => ({ content, page: 0, size: 30, totalElements: content.length, totalPages: 1, hasNext: false });

async function setup(page: Page, study = baseStudy, failure?: { status: number; code: string; message: string }) {
  const posts: CreateAssignmentRequest[] = [];
  let detailReads = 0;
  let release: (() => void) | undefined;
  let gate: Promise<void> | undefined;
  const detail: AssignmentDetail = {
    assignmentId: "55", title: "새 문제집", goal: "연습", tagId: "1", status: "SCHEDULED",
    startsAt: "2020-01-01T00:00:00Z", deadlineAt: "2030-01-01T00:00:00Z", isPreSolvedAllowed: false,
    problems: [{ problemId: "3", assignedRevisionId: "103", revisionNumber: 1, tags: ["해시"], title: "기존 난이도 3 문제", difficulty: 3, status: "ACTIVE", myCompletionType: null }],
    studyStats: { avgCompletionRate: 0, avgOnTimeRate: 0 }, memberProgress: [],
  };
  await mockApi(page, { handle: async (route, path, method) => {
    const ok = (data: unknown) => route.fulfill({ json: { success: true, data } });
    if (path === "/api/v1/members/me/tag-levels") { await ok({ content: [] }); return true; }
    if (path === "/api/v1/studies/2") { detailReads++; await ok(study); return true; }
    if (path === "/api/v1/problems") {
      const keyword = new URL(route.request().url()).searchParams.get("keyword") ?? "";
      await ok(pageOf(problems.filter((p) => p.title.includes(keyword)))); return true;
    }
    if (path === "/api/v1/studies/2/assignments/55") { await ok(detail); return true; }
    if (path === "/api/v1/studies/2/assignments") {
      if (method === "POST") {
        posts.push(route.request().postDataJSON());
        if (gate) await gate;
        if (failure) await route.fulfill({ status: failure.status, json: { success: false, error: failure } });
        else await ok({ assignmentId: "55", status: "SCHEDULED" });
      } else await ok(pageOf(posts.length ? [{ ...detail, activeProblemCount: 1, myStatus: null }] : []));
      return true;
    }
    return false;
  } });
  return { posts, reads: () => detailReads, hold: () => { gate = new Promise<void>((resolve) => { release = resolve; }); }, release: () => release?.() };
}
async function fill(page: Page) {
  await page.getByLabel("제목", { exact: true }).fill("새 문제집");
  await page.getByLabel("학습 목표").fill("연습");
  await page.getByLabel("대표 태그").selectOption("1");
  await page.getByLabel("시작 시각", { exact: true }).fill("2020-01-01T10:00");
  await page.getByLabel("마감 시각", { exact: true }).fill("2030-01-01T10:00");
}

for (const level of [1, 2, 3]) {
  test(`LV${level} 상한만 적용하고 개인 레벨·대표 태그로 선택을 제한하지 않는다`, async ({ page }) => {
    await setup(page, { ...baseStudy, skillLevel: level === 1 ? "LV1" : level === 2 ? "LV2" : "LV3" });
    await page.goto("/studies/2/assignments/new");
    for (let difficulty = 1; difficulty <= 3; difficulty++) {
      const button = page.getByRole("button", { name: `연습 문제 ${difficulty} 추가`, exact: true });
      if (difficulty <= level) await expect(button).toBeEnabled(); else await expect(button).toBeDisabled();
    }
  });
}

test("운영진 생성 진입·선택·중복 방지·생성 후 예정 상세와 목록 조회", async ({ page }) => {
  const fixture = await setup(page, { ...baseStudy, myMembership: { membershipId: "1", role: "MANAGER", status: "APPROVED" } });
  await page.goto("/studies/2");
  await page.getByRole("button", { name: "주간 문제집", exact: true }).click();
  await page.getByRole("link", { name: "문제집 만들기" }).click();
  await fill(page);
  await page.getByRole("button", { name: "연습 문제 2 추가", exact: true }).click();
  await expect(page.getByRole("button", { name: "연습 문제 2 추가", exact: true })).toBeDisabled();
  fixture.hold();
  await page.getByRole("button", { name: "문제집 생성", exact: true }).click();
  await expect.poll(() => fixture.posts.length).toBe(1);
  await expect(page.getByRole("button", { name: "생성 중…" })).toBeDisabled();
  fixture.release();
  await expect(page).toHaveURL(/\/assignments\/55$/);
  await expect(page.getByText("예정", { exact: true })).toBeVisible();
  expect(fixture.posts[0]).toMatchObject({ title: "새 문제집", goal: "연습", tagId: "1", problemIds: ["2"], isPreSolvedAllowed: false });
  expect(Date.parse(fixture.posts[0].startsAt)).toBeLessThan(Date.parse(fixture.posts[0].deadlineAt));
  await page.getByRole("link", { name: "← 스터디로 돌아가기" }).click();
  await page.getByRole("button", { name: "주간 문제집", exact: true }).click();
  await page.getByRole("button", { name: "예정", exact: true }).click();
  await expect(page.getByRole("heading", { name: "새 문제집" })).toBeVisible();
});

for (const failure of [
  { status: 403, code: "AUTH_ACCESS_DENIED", message: "접근 권한이 없습니다." },
  { status: 409, code: "STUDY_ALREADY_CLOSED", message: "종료된 스터디입니다." },
  { status: 409, code: "STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE", message: "선택 문제를 사용할 수 없습니다." },
  { status: 400, code: "COMMON_INVALID_REQUEST", message: "스터디의 난이도 상한을 초과했습니다." },
  { status: 503, code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "필요한 서비스를 조회할 수 없습니다." },
]) {
  test(`${failure.code}: 자동 재전송 없이 서버 메시지와 작성 내용 유지`, async ({ page }) => {
    const fixture = await setup(page, baseStudy, failure);
    await page.goto("/studies/2/assignments/new"); await fill(page);
    await page.getByRole("button", { name: "연습 문제 1 추가", exact: true }).click();
    await page.getByRole("button", { name: "문제집 생성", exact: true }).click();
    await expect(page.locator("form").getByRole("alert")).toContainText(failure.message);
    await expect(page.getByLabel("제목", { exact: true })).toHaveValue("새 문제집");
    await expect(page.getByRole("button", { name: "연습 문제 1 제거", exact: true })).toBeVisible();
    expect(fixture.posts).toHaveLength(1);
  });
}

for (const variation of [
  { myMembership: null },
  { myMembership: { membershipId: "1", role: "MEMBER", status: "APPROVED" } },
  { myMembership: { membershipId: "1", role: "LEADER", status: "PENDING" } },
  { status: "CLOSED" },
] satisfies Partial<StudyDetail>[]) {
  test(`생성 권한 없음 ${JSON.stringify(variation)}: 진입점과 직접 접근 차단`, async ({ page }) => {
    const fixture = await setup(page, { ...baseStudy, ...variation });
    await page.goto("/studies/2");
    await page.getByRole("button", { name: "주간 문제집", exact: true }).click();
    await expect(page.getByRole("link", { name: "문제집 만들기" })).toHaveCount(0);
    await page.goto("/studies/2/assignments/new");
    await expect(page.locator("form").getByRole("alert")).toContainText("승인된 스터디장·운영진만");
    await expect(page.getByRole("button", { name: "문제집 생성" })).toBeDisabled();
    expect(fixture.posts).toHaveLength(0);
  });
}

test("선택 개수 상한·삭제·시간 검증과 검색은 생성 요청을 보내지 않는다", async ({ page }) => {
  const fixture = await setup(page, { ...baseStudy, skillLevel: "LV3" });
  await page.goto("/studies/2/assignments/new"); await fill(page);
  for (let i = 1; i <= 20; i++) await page.getByRole("button", { name: `연습 문제 ${i} 추가`, exact: true }).click();
  await expect(page.getByRole("button", { name: "연습 문제 21 추가", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "연습 문제 1 제거", exact: true }).click();
  await expect(page.getByRole("button", { name: "연습 문제 21 추가", exact: true })).toBeEnabled();
  await page.getByLabel("마감 시각", { exact: true }).fill("2019-01-01T10:00");
  await page.getByRole("button", { name: "문제집 생성", exact: true }).click();
  await expect(page.locator("form").getByRole("alert")).toContainText("마감 시각은 시작 시각보다");
  await page.getByLabel("문제 검색").fill("연습 문제 21");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.getByRole("button", { name: "연습 문제 21 추가", exact: true })).toBeVisible();
  expect(fixture.posts).toHaveLength(0);
});

test("작성 중 수준 하향은 새 배정을 차단하고 기존 문제집은 유지한다", async ({ page }) => {
  const study = { ...baseStudy };
  const fixture = await setup(page, study);
  await page.goto("/studies/2/assignments/new"); await fill(page);
  await page.getByRole("button", { name: "연습 문제 2 추가", exact: true }).click();
  study.skillLevel = "LV1";
  await page.getByRole("button", { name: "문제집 생성", exact: true }).click();
  await expect(page.locator("form").getByRole("alert")).toContainText("난이도 상한을 초과한 문제를 제거");
  expect(fixture.posts).toHaveLength(0);
  await expect(page.getByLabel("제목", { exact: true })).toHaveValue("새 문제집");
  await page.goto("/studies/2/assignments/55");
  await expect(page.getByText("기존 난이도 3 문제", { exact: true })).toBeVisible();
});

test("목록 페이지 이동·실패 재시도는 폼을 제출하지 않고 선택을 유지한다", async ({ page }) => {
  const fixture = await setup(page);
  let failNext = true;
  await page.route("**/api/v1/problems?**", async (route) => {
    const currentPage = Number(new URL(route.request().url()).searchParams.get("page"));
    if (currentPage === 1 && failNext) {
      failNext = false;
      await route.fulfill({ status: 503, json: { success: false, error: { code: "COMMON_DEPENDENCY_UNAVAILABLE", message: "목록 조회 실패" } } });
      return;
    }
    await route.fulfill({ json: { success: true, data: {
      ...pageOf([problems[currentPage]]), page: currentPage, totalPages: 2, hasNext: currentPage === 0,
    } } });
  });
  await page.goto("/studies/2/assignments/new"); await fill(page);
  await page.getByRole("button", { name: "연습 문제 1 추가", exact: true }).click();
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await page.getByRole("button", { name: "문제 목록 다시 시도" }).click();
  await page.getByRole("button", { name: "연습 문제 2 추가", exact: true }).click();
  await expect(page.getByRole("button", { name: "연습 문제 1 제거", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "연습 문제 2 제거", exact: true })).toBeVisible();
  expect(fixture.posts).toHaveLength(0);
});

test("생성 직전 조회가 지연되어도 연속 제출은 조회·생성을 각각 한 번만 요청한다", async ({ page }) => {
  const fixture = await setup(page);
  await page.goto("/studies/2/assignments/new"); await fill(page);
  await page.getByRole("button", { name: "연습 문제 1 추가", exact: true }).click();
  let release = () => {};
  const pending = new Promise<void>((resolve) => { release = resolve; });
  let preflights = 0;
  await page.route("**/api/v1/studies/2", async (route) => {
    preflights++;
    await pending;
    await route.fulfill({ json: { success: true, data: baseStudy } });
  });
  try {
    await page.locator("form").evaluate((form: HTMLFormElement) => {
      form.requestSubmit();
      form.requestSubmit();
    });
    await expect.poll(() => preflights).toBe(1);
    await expect(page.getByRole("button", { name: "생성 중…" })).toBeDisabled();
    expect(fixture.posts).toHaveLength(0);
  } finally {
    release();
  }
  await expect(page).toHaveURL(/\/assignments\/55$/);
  expect(preflights).toBe(1);
  expect(fixture.posts).toHaveLength(1);
});

test("작성 중 운영진 권한이 회수되면 생성 요청을 보내지 않고 입력을 보존한다", async ({ page }) => {
  const study = { ...baseStudy };
  const fixture = await setup(page, study);
  await page.goto("/studies/2/assignments/new"); await fill(page);
  await page.getByRole("button", { name: "연습 문제 1 추가", exact: true }).click();
  study.myMembership = { membershipId: "1", role: "MEMBER", status: "APPROVED" };
  await page.getByRole("button", { name: "문제집 생성", exact: true }).click();
  await expect(page.getByLabel("제목", { exact: true })).toBeDisabled();
  await expect(page.getByLabel("제목", { exact: true })).toHaveValue("새 문제집");
  expect(fixture.posts).toHaveLength(0);
});
