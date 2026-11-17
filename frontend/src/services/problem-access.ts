import type { TagLevel } from "@/services/types";

type AccessProblem = { difficulty: number; tags: { tagId: string; name: string }[] };

/** 모든 태그의 레벨이 난이도를 충족해야 연습 풀이를 허용한다. (정책 및 상태 §8.3) */
export function problemAccess(problem: AccessProblem, levels: TagLevel[]) {
  const blockedTags = problem.tags.flatMap((tag) => {
    const level = levels.find((item) => item.tagId === tag.tagId);
    const maximum = level?.accessibleMaxDifficulty ?? 0;
    return maximum >= problem.difficulty ? [] : [{ ...tag, level: level?.level ?? "LV0", unentered: !level }];
  });
  return { allowed: problem.tags.length > 0 && blockedTags.length === 0, blockedTags };
}
