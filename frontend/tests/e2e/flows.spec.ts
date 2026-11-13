import { test, expect, type Page } from "@playwright/test";
import { APPROVAL_STUDY_ID, EXAM_ID, INSTANT_STUDY_ID, installClock, mockApi, type ApiFixtureOptions } from "./api-fixture";
import { readBrowserStorage } from "./storage-fixture";

const editor = (page: Page) => page.getByRole("textbox", { name: "코드 편집기" });
const code = (page: Page) => page.locator(".view-lines");

async function edit(page: Page, text: string) {
  await editor(page).press("ControlOrMeta+A");
  await editor(page).pressSequentially(text);
  await expect(code(page)).toContainText(text);
}

/** 서버 저장이 반영된 시험 Draft의 코드. 저장 완료 문구는 화면에 표시하지 않아 저장소로 확인한다. */
async function examDraft(page: Page, problemId: string) {
  const raw = await readBrowserStorage(page, `re-solve:editor:draft:exam:${EXAM_ID}:${problemId}`);
  return raw ? (JSON.parse(raw) as { code?: string }).code : null;
}

/** 앞서 등록한 응답을 지우고 다른 판정으로 다시 응답한다. 새로고침으로 화면의 제출 상태도 비운다. */
async function remock(page: Page, options: ApiFixtureOptions) {
  await page.unroute("**/api/v1/**");
  await mockApi(page, options);
  await page.reload();
  await expect(editor(page)).toBeVisible();
}

test("문제를 검색해 열고 언어별 코드를 분리·복원한 뒤 실행과 정답·오답·시스템 실패 제출 결과를 표시한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/problems");
  await page.getByPlaceholder("문제 제목 또는 번호 검색").fill("배열 합");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page).toHaveURL(/keyword=/);
  await expect(page.getByRole("link", { name: "고정 회차 배열 최댓값" })).toHaveCount(0);
  await page.getByRole("link", { name: "고정 회차 배열 합" }).click();
  await expect(page).toHaveURL(/\/problems\/1001$/);

  const language = page.getByLabel("코드 언어");
  await expect(editor(page)).toBeVisible();
  await language.selectOption("python3");
  await edit(page, "# Python saved draft");
  await language.selectOption("java");
  await expect(code(page)).not.toContainText("# Python saved draft");
  await edit(page, "// Java saved draft");
  await language.selectOption("python3");
  await expect(code(page)).toContainText("# Python saved draft");
  await expect.poll(async () => JSON.parse(
    await readBrowserStorage(page, 'resolve:draft:["7","practice","1001","java"]') ?? "{}").code).toBe("// Java saved draft");
  await page.reload();
  await expect(language).toHaveValue("python3");
  await expect(code(page)).toContainText("# Python saved draft");

  const results = page.locator(".results");
  await page.getByRole("button", { name: "실행", exact: true }).click();
  const sample = page.getByRole("article", { name: "테스트 1" });
  await expect(sample).toContainText("공개 예제");
  await expect(sample).toContainText("통과");
  await expect(sample).toContainText("기대값: 3");

  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(results.getByText("AC 정답", { exact: true })).toBeVisible();
  await expect(results).toContainText("5 / 5 통과");

  await remock(page, { verdict: "WA" });
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(results.getByText("WA 오답", { exact: true })).toBeVisible();
  await expect(results).toContainText("3 / 5 통과");
  // 숨김 테스트는 통과 수만 알려 주고 기대값·반환값은 노출하지 않는다.
  await expect(results).not.toContainText("기대값");
  await expect(results).not.toContainText("반환값");

  await remock(page, { verdict: "FAILED" });
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(results.getByText("채점 시스템 실패: ENGINE_UNAVAILABLE", { exact: true })).toBeVisible();
  await expect(results.getByText("시스템 오류", { exact: true })).toBeVisible();
});

test("스터디를 만들고 승인 가입 스터디에 신청·취소하며 중복 신청 오류는 서버 사유로 안내한다", async ({ page }) => {
  let duplicate = false;
  await mockApi(page, {
    handle: (route, path, method) => {
      if (!duplicate || method !== "POST" || path !== `/api/v1/studies/${APPROVAL_STUDY_ID}/memberships`) return false;
      return route.fulfill({ status: 409, json: { success: false, error: {
        code: "STUDY_ALREADY_APPLIED", message: "이미 가입 신청한 스터디입니다." } } }).then(() => true);
    },
  });
  await page.goto("/studies/new");
  await page.getByLabel("스터디 이름").fill("검증용 스터디");
  await page.getByLabel("스터디 소개").fill("함께 알고리즘 문제를 매일 연습하는 스터디입니다.");
  await page.getByLabel("학습 목표").selectOption("STUDY");
  await page.getByLabel("최대 인원").fill("4");
  await page.getByLabel("승인 후 가입").check();
  const created = page.waitForRequest((request) => request.method() === "POST"
    && new URL(request.url()).pathname === "/api/v1/studies");
  await page.getByRole("button", { name: "스터디 생성", exact: true }).click();
  expect((await created).postDataJSON()).toEqual({
    name: "검증용 스터디", description: "함께 알고리즘 문제를 매일 연습하는 스터디입니다.", goal: "STUDY",
    skillLevel: "LV0", preferredLanguage: "java", activeTimeSlot: "MORNING", capacity: 4,
    joinType: "APPROVAL", isAutoReopen: false,
  });
  await expect(page).toHaveURL(/\/studies\/13$/);
  await expect(page.getByRole("heading", { name: "검증용 스터디" })).toBeVisible();
  await expect(page.getByRole("button", { name: "탈퇴 (위임 필요)", exact: true })).toBeVisible();

  await page.goto(`/studies/${APPROVAL_STUDY_ID}`);
  await page.getByRole("button", { name: "신청하기", exact: true }).click();
  await expect(page.getByRole("button", { name: "신청 취소", exact: true })).toBeVisible();
  page.once("dialog", (dialog) => {
    expect(dialog.message()).toBe("신청을 취소하시겠습니까?");
    return dialog.accept();
  });
  await page.getByRole("button", { name: "신청 취소", exact: true }).click();
  await expect(page.getByRole("button", { name: "신청하기", exact: true })).toBeVisible();

  duplicate = true;
  await page.getByRole("button", { name: "신청하기", exact: true }).click();
  const toast = page.locator(".toast");
  await expect(toast).toHaveText("이미 신청하셨습니다.");
  await expect(toast).not.toHaveText("저장하지 못했습니다. 다시 시도해 주세요.");
  await expect(page.getByRole("button", { name: "신청하기", exact: true })).toBeVisible();
});

test("시험에 참가해 문제별 Draft를 유지하며 제출·종료하고 남은 시간이 0이 되면 자동 종료해 결과로 이동한다", async ({ page }) => {
  const finishes: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === `/api/v1/exams/${EXAM_ID}/finish`) finishes.push(request.method());
  });
  await mockApi(page);
  await page.goto(`/exams/${EXAM_ID}`);
  await expect(page.getByRole("heading", { name: "주간 모의 코테" })).toBeVisible();
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await expect(page.getByRole("button", { name: "참가 취소", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "응시 시작", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/room$`));

  await expect(editor(page)).toBeVisible();
  await edit(page, "// exam first draft");
  await expect.poll(() => examDraft(page, "1001")).toBe("// exam first draft");
  await page.getByRole("button", { name: /문제 2/ }).click();
  await expect(page.getByRole("heading", { name: "고정 회차 배열 최댓값" })).toBeVisible();
  await expect(code(page)).not.toContainText("// exam first draft");
  await edit(page, "// exam second draft");
  await expect.poll(() => examDraft(page, "1002")).toBe("// exam second draft");
  await page.getByRole("button", { name: /문제 1/ }).click();
  await expect(code(page)).toContainText("// exam first draft");
  await page.getByRole("button", { name: /문제 2/ }).click();
  await expect(code(page)).toContainText("// exam second draft");

  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.getByText("제출 완료", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "응시 종료", exact: true }).click();
  await page.getByRole("button", { name: "전체 제출 및 종료", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/result$`));
  await expect(page.getByRole("heading", { name: "도전의 기록" })).toBeVisible();
  await expect(page.getByText("2 / 2문제 정답", { exact: true })).toBeVisible();
  expect(finishes).toEqual(["POST"]);

  // 만료: 종료 5초 전에 입실해 시계를 넘기면 사용자가 누르지 않아도 종료 요청 후 결과로 간다.
  await page.unroute("**/api/v1/**");
  await mockApi(page, { examEndsInSeconds: 5 });
  await installClock(page);
  await page.goto(`/exams/${EXAM_ID}/room`);
  await expect(editor(page)).toBeVisible();
  await expect(page.getByLabel("남은 시간")).toHaveText(/00:0[1-5]/);
  await page.clock.runFor(6000);
  await expect(page).toHaveURL(new RegExp(`/exams/${EXAM_ID}/result$`));
  expect(finishes).toEqual(["POST", "POST"]);
});

test("비로그인 보호 경로·빈 검색·없는 문제·조회 실패 재시도를 각 상태로 안내한다", async ({ page }) => {
  await mockApi(page, { loggedIn: false });
  await page.goto("/studies");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fstudies$/);
  // 문제 화면은 보호 경로라 비로그인 사용자는 실행·제출 버튼에 닿기 전에 원래 주소를 들고 로그인으로 간다.
  await page.goto("/problems/1001?tab=run");
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fproblems%2F1001%3Ftab%3Drun$/);
  await expect(page.getByRole("button", { name: "실행", exact: true })).toHaveCount(0);

  await page.unroute("**/api/v1/**");
  let failStudy = true;
  await mockApi(page, {
    handle: async (route, path) => {
      if (path === "/api/v1/problems/9999") {
        await route.fulfill({ status: 404, json: { success: false, error: {
          code: "PROBLEM_NOT_FOUND", message: "문제를 찾을 수 없습니다." } } });
        return true;
      }
      if (path === `/api/v1/studies/${INSTANT_STUDY_ID}` && failStudy) {
        // 프록시가 돌려주는 HTML 오류 본문처럼 명세 형식이 아닌 5xx를 흉내 낸다.
        await route.fulfill({ status: 500, contentType: "text/html", body: "<h1>Internal Server Error</h1>" });
        return true;
      }
      return false;
    },
  });

  await page.goto("/problems/1001");
  await expect(editor(page)).toBeVisible();
  await page.getByRole("button", { name: "실행", exact: true }).click();
  await expect(page.getByRole("article", { name: "테스트 1" })).toBeVisible();
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page.locator(".results").getByText("AC 정답", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/problems\/1001$/);

  await page.goto("/problems?keyword=없는문제");
  await expect(page.getByRole("heading", { name: "검색 결과가 없어요" })).toBeVisible();

  await page.goto("/problems/9999");
  const notFound = page.getByRole("alert").filter({ hasText: "불러오지 못했어요" });
  await expect(notFound).toContainText("문제를 찾을 수 없습니다.");
  await expect(editor(page)).toHaveCount(0);

  await page.goto(`/studies/${INSTANT_STUDY_ID}`);
  const failure = page.getByRole("alert").filter({ hasText: "불러오지 못했어요" });
  await expect(failure).toContainText("일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.");
  await expect(failure).not.toContainText("Internal Server Error");
  failStudy = false;
  await failure.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.getByRole("heading", { name: "즉시 가입 스터디" })).toBeVisible();
  await expect(failure).toHaveCount(0);
});

test("로그인 상태에서 토큰 갱신까지 401이면 버튼 동작 후 원래 경로를 들고 로그인으로 이동한다", async ({ page }) => {
  let expired = false;
  await mockApi(page, {
    handle: async (route, path) => {
      if (!expired) return false;
      if (path === "/api/v1/auth/token/refresh" || path === `/api/v1/exams/${EXAM_ID}/participants`) {
        await route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } } });
        return true;
      }
      return false;
    },
  });
  await page.goto(`/exams/${EXAM_ID}`);
  await expect(page.getByRole("button", { name: "참가 신청", exact: true })).toBeVisible();
  expired = true;
  await page.getByRole("button", { name: "참가 신청", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/login\\?returnTo=%2Fexams%2F${EXAM_ID}$`));
});

// 결함: 연습 제출은 useAction을 거치지 않아 세션 만료(UNAUTHORIZED)를 로그인 이동 대신 결과 영역 오류 문구로만 보여 준다.
test.fixme("연습 제출 중 토큰 갱신까지 401이면 원래 문제 경로를 들고 로그인으로 이동한다", async ({ page }) => {
  let expired = false;
  await mockApi(page, {
    handle: async (route, path) => {
      if (!expired) return false;
      if (path === "/api/v1/auth/token/refresh" || path === "/api/v1/submissions") {
        await route.fulfill({ status: 401, json: { success: false, error: { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } } });
        return true;
      }
      return false;
    },
  });
  await page.goto("/problems/1001");
  await expect(editor(page)).toBeVisible();
  expired = true;
  await page.getByRole("button", { name: "제출", exact: true }).click();
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fproblems%2F1001$/);
});

test("Draft 저장소 쓰기가 실패해도 편집 중인 코드를 유지하고 복사 보관 안내를 알린다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/problems/1001");
  await expect(editor(page)).toBeVisible();
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key: string, value: string) {
      if (key.startsWith("resolve:draft:")) throw new DOMException("quota", "QuotaExceededError");
      return original.call(this, key, value);
    };
  });
  await editor(page).press("ControlOrMeta+A");
  await editor(page).pressSequentially("# preserve on failure");
  await expect(page.getByRole("status").filter({ hasText: "저장 실패 · 코드를 복사해 보관해 주세요." })).toBeVisible();
  await expect(code(page)).toContainText("# preserve on failure");
  await expect(editor(page)).toBeEditable();
});

test("부화 모달은 닫기 버튼에 포커스를 주고 Esc로 연 버튼에 되돌리며 어두운 테마는 새로고침 후에도 유지한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/");
  const hatch = page.getByRole("button", { name: "알 부화하기" }).first();
  await hatch.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await page.getByRole("button", { name: "부화시키기", exact: true }).click();
  await expect(dialog).toHaveCount(0);

  await page.getByRole("button", { name: "테마 전환", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  const opener = page.getByRole("button", { name: "알 부화하기" }).first();
  await opener.focus();
  await page.keyboard.press("Enter");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "닫기", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(opener).toBeFocused();
});
