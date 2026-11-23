import { describe, expect, it } from "vitest";
import { canEditPost, canModeratePost, canReadPosts, canWritePost, postInputError } from "@/features/study-post-policy";
import type { StudyDetail } from "@/services/types";

const study: StudyDetail = {
  studyId: "1", name: "스터디", description: "", goal: "STUDY", skillLevel: "LV2",
  preferredLanguage: "java", activeTimeSlot: "EVENING", capacity: 10, memberCount: 2,
  status: "ACTIVE", joinType: "INSTANT", isAutoReopen: false, version: 1,
  leader: null, myMembership: { membershipId: "1", role: "MEMBER", status: "APPROVED" }, createdAt: "",
};
const withRole = (role: "LEADER" | "MANAGER" | "MEMBER", status: "APPROVED" | "PENDING" = "APPROVED"): StudyDetail =>
  ({ ...study, myMembership: { membershipId: "1", role, status } });

describe("스터디 게시판 버튼 노출 정책", () => {
  it("승인된 구성원만 게시판을 보고, 종료된 스터디도 조회는 허용한다", () => {
    expect(canReadPosts(study)).toBe(true);
    expect(canReadPosts({ ...study, status: "CLOSED" })).toBe(true);
    expect(canReadPosts({ ...study, myMembership: null })).toBe(false);
    expect(canReadPosts(withRole("MEMBER", "PENDING"))).toBe(false);
  });
  it("종료된 스터디에서는 글쓰기를 숨긴다", () => {
    expect(canWritePost(study)).toBe(true);
    expect(canWritePost({ ...study, status: "CLOSED" })).toBe(false);
  });
  it("공지·고정·숨김은 종료되지 않은 스터디의 스터디장·운영진에게만 보인다", () => {
    expect(canModeratePost(withRole("LEADER"))).toBe(true);
    expect(canModeratePost(withRole("MANAGER"))).toBe(true);
    expect(canModeratePost(withRole("MEMBER"))).toBe(false);
    expect(canModeratePost({ ...withRole("LEADER"), status: "CLOSED" })).toBe(false);
  });
  it("수정·삭제는 작성자 본인에게만 보이고 로그인 회원을 모르면 숨긴다", () => {
    expect(canEditPost(study, "7", "7")).toBe(true);
    expect(canEditPost(study, "7", "8")).toBe(false);
    expect(canEditPost(study, "7", null)).toBe(false);
    expect(canEditPost({ ...study, status: "CLOSED" }, "7", "7")).toBe(false);
  });
});

describe("게시글 입력 검증", () => {
  it("제목·본문이 공백뿐이면 거절하고 경계 길이는 허용한다", () => {
    expect(postInputError("  ", "본문")).toBe("제목을 입력해 주세요.");
    expect(postInputError("제목", " \t ")).toBe("본문을 입력해 주세요.");
    expect(postInputError("가".repeat(200), "가".repeat(10_000))).toBeNull();
  });
  it("제목 201자·본문 10,001자는 거절한다", () => {
    expect(postInputError("가".repeat(201), "본문")).toBe("제목은 200자 이하로 입력해 주세요.");
    expect(postInputError("제목", "가".repeat(10_001))).toBe("본문은 10,000자 이하로 입력해 주세요.");
  });
});
