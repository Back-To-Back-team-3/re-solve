import { describe, expect, it } from "vitest";
import { assignmentDifficultyLimit, canCreateAssignment, isAssignmentDifficultyAllowed } from "@/features/study-assignment-policy";
import type { StudyDetail } from "@/services/types";

const study: StudyDetail = {
  studyId: "1", name: "스터디", description: "", goal: "STUDY", skillLevel: "LV2",
  preferredLanguage: "java", activeTimeSlot: "EVENING", capacity: 10, memberCount: 2,
  status: "ACTIVE", joinType: "INSTANT", isAutoReopen: false, version: 1,
  leader: null, myMembership: { membershipId: "1", role: "LEADER", status: "APPROVED" }, createdAt: "",
};
describe("문제집 새 배정 정책", () => {
  it.each([1, 2, 3])("LV%i의 난이도 상한과 경계를 적용한다", (level) => {
    expect(assignmentDifficultyLimit(`LV${level}`)).toBe(level);
    expect(isAssignmentDifficultyAllowed(level, level)).toBe(true);
    expect(isAssignmentDifficultyAllowed(level + 1, level)).toBe(false);
    expect(isAssignmentDifficultyAllowed(0, level)).toBe(false);
    expect(isAssignmentDifficultyAllowed(1.5, level)).toBe(false);
  });
  it.each(["LV0", "MASTER", "UNKNOWN"])("구형·미확정 수준 %s를 임의 변환하지 않는다", (level) => {
    expect(assignmentDifficultyLimit(level)).toBeNull();
  });
  it("승인된 운영진만 종료되지 않은 스터디에서 생성한다", () => {
    expect(canCreateAssignment(study)).toBe(true);
    expect(canCreateAssignment({ ...study, myMembership: { membershipId: "2", role: "MANAGER", status: "APPROVED" } })).toBe(true);
    expect(canCreateAssignment({ ...study, status: "CLOSED" })).toBe(false);
    expect(canCreateAssignment({ ...study, myMembership: null })).toBe(false);
    expect(canCreateAssignment({ ...study, myMembership: { membershipId: "2", role: "MEMBER", status: "APPROVED" } })).toBe(false);
    expect(canCreateAssignment({ ...study, myMembership: { membershipId: "2", role: "LEADER", status: "PENDING" } })).toBe(false);
  });
});
