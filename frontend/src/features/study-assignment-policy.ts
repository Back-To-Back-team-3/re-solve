import type { StudyDetail } from "@/services/types";

export function canCreateAssignment(study: StudyDetail): boolean {
  return study.status !== "CLOSED" && study.myMembership?.status === "APPROVED"
    && (study.myMembership.role === "LEADER" || study.myMembership.role === "MANAGER");
}

// 구형 수준 값의 데이터 변환은 Study가 담당한다. 미확정 값은 임의로 매핑하지 않는다.
export function assignmentDifficultyLimit(level: string): number | null {
  return level === "LV1" ? 1 : level === "LV2" ? 2 : level === "LV3" ? 3 : null;
}

export function isAssignmentDifficultyAllowed(difficulty: number, limit: number): boolean {
  return Number.isInteger(difficulty) && difficulty >= 1 && difficulty <= limit;
}
