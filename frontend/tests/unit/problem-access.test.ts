import { describe, expect, it } from "vitest";
import { problemAccess } from "@/services/problem-access";
import type { TagLevel } from "@/services/types";

const level = (tagId: string, maximum: number, value = `LV${maximum}`): TagLevel => ({
  tagId, level: value, accessibleMaxDifficulty: maximum, achievements: [],
  diagnosis: { isEligible: false, cooldownEndsAt: null, inProgressDiagnosisId: null }, nextPromotion: null,
});
const problem = { difficulty: 2, tags: [{ tagId: "1", name: "배열" }, { tagId: "2", name: "정렬" }] };

describe("연습 문제 레벨 제한", () => {
  it("모든 태그가 난이도를 충족할 때만 허용하고 마스터도 난이도 3까지 허용한다", () => {
    expect(problemAccess(problem, [level("1", 2), level("2", 3, "MASTER")]).allowed).toBe(true);
    expect(problemAccess(problem, [level("1", 3), level("2", 1)]).blockedTags.map((tag) => tag.tagId)).toEqual(["2"]);
  });
  it("누락 태그는 미진입이며 진단 중 LV0은 재진입 대상으로 보지 않는다", () => {
    const result = problemAccess(problem, [level("2", 0)]);
    expect(result.allowed).toBe(false);
    expect(result.blockedTags.map((tag) => tag.unentered)).toEqual([true, false]);
  });
  it("태그가 없는 응답을 접근 가능으로 처리하지 않는다", () => {
    expect(problemAccess({ difficulty: 1, tags: [] }, []).allowed).toBe(false);
  });
});
