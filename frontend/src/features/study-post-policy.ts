import type { StudyDetail } from "@/services/types";

/** 게시판은 승인된 구성원만 본다(정책 및 상태 §7 커뮤니티). 종료된 스터디도 조회는 허용한다. */
export function canReadPosts(study: StudyDetail): boolean {
  return study.myMembership?.status === "APPROVED";
}

/** 글쓰기·수정·삭제·운영진 관리 버튼은 종료되지 않은 스터디의 승인된 구성원에게만 보인다. 최종 판단은 서버가 한다. */
export function canWritePost(study: StudyDetail): boolean {
  return canReadPosts(study) && study.status !== "CLOSED";
}

/** 공지 지정·상단 고정·숨김은 운영진(LEADER·MANAGER)만 한다(api-spec §5.5.2, §5.5.5). */
export function canModeratePost(study: StudyDetail): boolean {
  return canWritePost(study) && (study.myMembership?.role === "LEADER" || study.myMembership?.role === "MANAGER");
}

/** 수정·삭제는 작성자 본인만 한다(api-spec §5.5.4). 로그인 회원을 알 수 없으면 숨긴다. */
export function canEditPost(study: StudyDetail, authorId: string, memberId: string | null | undefined): boolean {
  return canWritePost(study) && memberId != null && authorId === memberId;
}

/** 제목·본문 길이 상한(api-spec §5.5.2). 서버도 같은 기준으로 거절한다. */
export const POST_TITLE_MAX = 200;
export const POST_CONTENT_MAX = 10_000;

/** 공백뿐인 제목·본문과 길이 초과를 서버 요청 전에 막고 안내 문구를 돌려준다. 제목은 앞뒤 공백을 빼고 보낸다. */
export function postInputError(title: string, content: string): string | null {
  if (!title.trim()) return "제목을 입력해 주세요.";
  if (title.trim().length > POST_TITLE_MAX) return `제목은 ${POST_TITLE_MAX}자 이하로 입력해 주세요.`;
  if (!content.trim()) return "본문을 입력해 주세요.";
  if (content.length > POST_CONTENT_MAX) return "본문은 10,000자 이하로 입력해 주세요.";
  return null;
}
