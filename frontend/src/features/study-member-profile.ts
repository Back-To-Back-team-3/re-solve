import type { Tag } from "@/services/types";

/** 카탈로그 태그 목록(API 명세서 2.5.1)에서 태그 ID → 이름 조회표를 만든다. */
export function tagNameMap(tags: readonly Pick<Tag, "id" | "name">[]): ReadonlyMap<string, string> {
  return new Map(tags.map((tag) => [tag.id, tag.name]));
}

/**
 * 가입 신청자의 태그별 레벨을 "이름 레벨" 형식으로 앞에서부터 `limit`개 보여 준다.
 * 응답은 태그 ID만 담으므로(API 명세서 5.2.4) 화면이 이름을 붙이고, 찾지 못한 태그는 ID를 그대로 보여 준다.
 * 숫자 문자열 키는 객체 순회 규칙에 따라 ID 오름차순으로 나온다.
 */
export function formatTagLevels(
  tagLevels: Record<string, string>,
  tagNames: ReadonlyMap<string, string>,
  limit = 2,
): string {
  return Object.entries(tagLevels)
    .slice(0, limit)
    .map(([tagId, level]) => `${tagNames.get(tagId) ?? tagId} ${level}`)
    .join(" · ");
}
