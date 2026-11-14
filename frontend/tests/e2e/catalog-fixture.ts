import type { Route } from "@playwright/test";
import type { ProblemListItem } from "../../src/services/types";

/**
 * 화면 전체를 감싸는 카탈로그(태그 API 명세서 2.5.1, 문제 목록 2.1.1)의 기본 응답.
 * 모든 E2E fixture가 공유하므로 다른 fixture를 import하지 않는다(순환 참조 방지).
 */
export const catalogTags = [
  { tagId: "1", name: "배열", displayOrder: 1 },
  { tagId: "2", name: "정렬", displayOrder: 2 },
];

export const catalogProblems: ProblemListItem[] = [
  {
    problemId: "1001", problemRevisionId: "2001", revisionNumber: 1, title: "고정 회차 배열 합", difficulty: 1,
    tags: [{ tagId: "1", name: "배열" }], publishedAt: "2026-11-13T00:00:00.000000+09:00",
    solvedStatus: "UNSOLVED", supportedLanguages: ["java", "python3"],
  },
  {
    problemId: "1002", problemRevisionId: "2002", revisionNumber: 1, title: "고정 회차 배열 최댓값", difficulty: 2,
    tags: [{ tagId: "2", name: "정렬" }], publishedAt: "2026-11-13T00:00:00.000000+09:00",
    solvedStatus: "UNSOLVED", supportedLanguages: ["java", "python3"],
  },
];

/** 카탈로그 경로면 응답하고 true를 돌려준다. 문제 목록은 `keyword`로 제목·번호를 거른다. */
export async function fulfillCatalog(route: Route, path: string): Promise<boolean> {
  if (path === "/api/v1/problems/tags") {
    await route.fulfill({ json: { success: true, message: null, data: { content: catalogTags } } });
    return true;
  }
  if (path === "/api/v1/problems") {
    const keyword = new URL(route.request().url()).searchParams.get("keyword") ?? "";
    const content = catalogProblems.filter((p) => p.title.includes(keyword) || p.problemId === keyword);
    await route.fulfill({ json: { success: true, message: null, data: {
      content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false,
    } } });
    return true;
  }
  return false;
}
