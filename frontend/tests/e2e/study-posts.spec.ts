import { expect, test, type Page, type Route } from "@playwright/test";
import { member, mockApi } from "./api-fixture";
import type { MembershipRole, PostDetail, PostStatus, StudyDetail } from "@/services/types";

const STUDY = "/api/v1/studies/2";

type StoredPost = Omit<PostDetail, "comments"> & { status: PostStatus };

const study = (role: MembershipRole, status: StudyDetail["status"] = "ACTIVE"): StudyDetail => ({
  studyId: "2", name: "게시판 스터디", description: "함께 공부합니다.",
  goal: "STUDY", skillLevel: "LV2", preferredLanguage: "java", activeTimeSlot: "EVENING",
  capacity: 10, memberCount: 3, status, joinType: "INSTANT", isAutoReopen: false,
  version: 1, leader: { memberId: "1", nickname: "스터디장" },
  myMembership: { membershipId: "9", role, status: "APPROVED" }, createdAt: "2026-11-01T10:00:00+09:00",
});

const stored = (postId: string, authorId: string, title: string, minute: number, extra: Partial<StoredPost> = {}): StoredPost => ({
  postId, title, content: `${title} 본문`, author: { memberId: authorId, nickname: authorId === member.memberId ? member.nickname : "다른 구성원" },
  isPinned: false, isNotice: false, status: "VISIBLE",
  createdAt: `2026-11-20T10:${String(minute).padStart(2, "0")}:00+09:00`, updatedAt: "2026-11-20T10:00:00+09:00", ...extra,
});

/** 게시글 API(api-spec §5.5)를 메모리 목록으로 흉내 낸다. 숨김·삭제 글은 목록·상세에서 빠지고 상세는 404다. */
async function setup(page: Page, detail: StudyDetail, initial: StoredPost[]) {
  const posts = [...initial];
  const created: unknown[] = [];
  const ok = (route: Route, data: unknown, status = 200) => route.fulfill({ status, json: { success: true, message: null, data } });
  const visible = () => posts.filter((p) => p.status === "VISIBLE");
  page.on("dialog", (dialog) => dialog.accept());
  await mockApi(page, {
    handle: async (route, path, method) => {
      if (path === STUDY) { await ok(route, detail); return true; }
      if (path === `${STUDY}/posts`) {
        if (method === "POST") {
          const body = route.request().postDataJSON() as { title: string; content: string; isNotice: boolean };
          created.push(body);
          const post = stored(String(100 + posts.length), member.memberId, body.title, 59, { content: body.content, isNotice: body.isNotice });
          posts.push(post);
          await ok(route, { postId: post.postId }, 201);
          return true;
        }
        const noticeOnly = new URL(route.request().url()).searchParams.get("isNotice") === "true";
        const content = visible()
          .filter((p) => !noticeOnly || p.isNotice)
          .sort((a, b) => Number(b.isPinned) - Number(a.isPinned) || b.createdAt.localeCompare(a.createdAt))
          .map((p) => ({
            postId: p.postId, title: p.title, authorId: p.author.memberId, authorNickname: p.author.nickname,
            isPinned: p.isPinned, isNotice: p.isNotice, commentCount: 0, createdAt: p.createdAt,
          }));
        await ok(route, { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, hasNext: false });
        return true;
      }
      const match = path.match(/^\/api\/v1\/studies\/2\/posts\/(\d+)(\/moderation)?$/);
      if (!match) return false;
      const post = posts.find((p) => p.postId === match[1]);
      if (match[2]) {
        const body = route.request().postDataJSON() as { isPinned: boolean; isNotice: boolean; isHidden: boolean };
        Object.assign(post!, { isPinned: body.isPinned, isNotice: body.isNotice, status: body.isHidden ? "HIDDEN" : "VISIBLE" });
        await ok(route, { postId: post!.postId, isPinned: body.isPinned, isNotice: body.isNotice, status: post!.status });
        return true;
      }
      if (!post || post.status !== "VISIBLE") {
        await route.fulfill({ status: 404, json: { success: false, error: { code: "STUDY_POST_NOT_FOUND", message: "게시글을 찾을 수 없습니다." } } });
        return true;
      }
      if (method === "DELETE") { post.status = "DELETED"; await route.fulfill({ status: 204 }); return true; }
      if (method === "PATCH") {
        Object.assign(post, route.request().postDataJSON() as { title: string; content: string });
        await ok(route, { postId: post.postId });
        return true;
      }
      const body: PostDetail = {
        postId: post.postId, title: post.title, content: post.content, author: post.author,
        isPinned: post.isPinned, isNotice: post.isNotice, comments: [], createdAt: post.createdAt, updatedAt: post.updatedAt,
      };
      await ok(route, body);
      return true;
    },
  });
  return { created };
}

async function openBoard(page: Page) {
  await page.goto("/studies/2");
  await page.getByRole("button", { name: "게시판", exact: true }).click();
}

const titles = (page: Page) => page.locator("li.card b");

test("구성원이 글을 작성·조회·수정·삭제하고 목록은 고정 글을 먼저 보여 준다", async ({ page }) => {
  const { created } = await setup(page, study("MEMBER"), [
    stored("1", "8", "예전 글", 1),
    stored("2", "8", "고정된 안내", 0, { isPinned: true }),
  ]);
  await openBoard(page);
  await expect(titles(page)).toHaveText(["고정된 안내", "예전 글"]);

  await page.getByRole("link", { name: "글쓰기" }).click();
  await page.getByLabel("제목", { exact: true }).fill("  이번 주 풀이 공유  ");
  await page.getByLabel("본문", { exact: true }).fill("BFS로 풀었습니다.\n두 번째 줄");
  await page.getByRole("button", { name: "등록" }).click();
  await expect(page.getByRole("heading", { name: "이번 주 풀이 공유" })).toBeVisible();
  expect(created).toEqual([{ title: "이번 주 풀이 공유", content: "BFS로 풀었습니다.\n두 번째 줄", isNotice: false }]);

  await page.getByRole("button", { name: "수정" }).click();
  await page.getByLabel("제목", { exact: true }).fill("수정한 제목");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("heading", { name: "수정한 제목" })).toBeVisible();

  await page.getByRole("button", { name: "삭제" }).click();
  await expect(page).toHaveURL(/\/studies\/2\?tab=board$/);
  await expect(titles(page)).toHaveText(["고정된 안내", "예전 글"]);
});

test("일반 구성원에게는 공지 지정·운영진 관리 버튼과 남의 글 수정·삭제 버튼이 없다", async ({ page }) => {
  await setup(page, study("MEMBER"), [stored("1", "8", "다른 사람 글", 1)]);
  await page.goto("/studies/2/posts/new");
  await expect(page.getByLabel("제목", { exact: true })).toBeVisible();
  await expect(page.getByLabel("공지로 등록")).toHaveCount(0);

  await page.goto("/studies/2/posts/1");
  await expect(page.getByRole("heading", { name: "다른 사람 글" })).toBeVisible();
  for (const name of ["수정", "삭제", "상단 고정", "공지 지정", "숨기기"])
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(0);
});

test("운영진이 글을 숨기면 게시판으로 돌아가고 목록에서 빠진다", async ({ page }) => {
  await setup(page, study("MANAGER"), [stored("1", "8", "부적절한 글", 1), stored("2", "8", "남길 글", 0)]);
  await page.goto("/studies/2/posts/1");
  await page.getByRole("button", { name: "상단 고정" }).click();
  await expect(page.getByRole("button", { name: "고정 해제" })).toBeVisible();
  await page.getByRole("button", { name: "숨기기" }).click();
  await expect(page).toHaveURL(/\/studies\/2\?tab=board$/);
  await expect(titles(page)).toHaveText(["남길 글"]);
});

test("종료된 스터디에서는 쓰기 버튼을 숨기고 목록·상세 조회는 허용한다", async ({ page }) => {
  await setup(page, study("LEADER", "CLOSED"), [stored("1", member.memberId, "지난 공지", 1, { isNotice: true })]);
  await openBoard(page);
  await expect(titles(page)).toHaveText(["지난 공지"]);
  await expect(page.getByRole("link", { name: "글쓰기" })).toHaveCount(0);

  await page.getByRole("link", { name: "지난 공지" }).click();
  await expect(page.getByRole("heading", { name: "지난 공지" })).toBeVisible();
  for (const name of ["수정", "삭제", "상단 고정", "공지 해제", "숨기기"])
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(0);
});
