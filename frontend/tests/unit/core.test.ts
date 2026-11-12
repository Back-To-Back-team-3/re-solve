import { beforeEach, describe, expect, it, vi } from "vitest";
import { seed } from "../../src/mocks/fixtures";
import {
  remainingSeconds,
  resolveSubmission,
} from "../../src/services/mock-api";
import { draftKey, readDraft, saveDraft } from "../../src/stores/workspace";
describe("draft persistence", () => {
  beforeEach(() => {
    const map = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (k: string) => map.get(k) || null,
      setItem: (k: string, v: string) => map.set(k, v),
    });
  });
  it("isolates user, language, problem and exam", () => {
    const keys = [
      draftKey("a", "1001", "java"),
      draftKey("b", "1001", "java"),
      draftKey("a", "1001", "cpp"),
      draftKey("a", "1002", "java"),
      draftKey("a", "1001", "java", "exam-1"),
    ];
    expect(new Set(keys).size).toBe(5);
  });
  it("restores an intentionally empty code draft", () => {
    const key = draftKey("a", "1001", "java"),
      draft = { code: "", revisionId: "2001", savedAt: 123 };
    saveDraft(key, draft);
    expect(readDraft(key)).toEqual(draft);
    expect(readDraft("another")).toBeNull();
  });
  it("surfaces storage errors instead of reporting a save", () => {
    vi.stubGlobal("localStorage", {
      setItem: () => {
        throw new Error("quota");
      },
    });
    expect(() =>
      saveDraft("x", { code: "keep me", revisionId: "2001", savedAt: 0 }),
    ).toThrow("quota");
  });
});
describe("mock judgement and timer", () => {
  it("keeps processing state separate from verdict and reaches a terminal state", () => {
    const s = {
      ...seed(1000).submissions[0],
      createdAt: 1000,
      state: "QUEUED" as const,
      verdict: null,
      scenario: "WA" as const,
    };
    expect(resolveSubmission(s, 1000).state).toBe("QUEUED");
    expect(resolveSubmission(s, 1400).state).toBe("JUDGING");
    expect(resolveSubmission(s, 1800).state).toBe("JUDGING");
    expect(resolveSubmission(s, 2400)).toMatchObject({
      state: "COMPLETED",
      verdict: "WA",
    });
    expect(resolveSubmission({ ...s, scenario: "FAILED" }, 2400)).toMatchObject(
      { state: "FAILED", verdict: null },
    );
  });
  it("counts from timestamps, handles tab suspension, and clamps expiration", () => {
    expect(remainingSeconds(10000, 8500)).toBe(2);
    expect(remainingSeconds(10000, 10000)).toBe(0);
    expect(remainingSeconds(10000, 50000)).toBe(0);
  });
});

import { examDrafts } from "../../src/stores/workspace";
it("final exam submission selects the most recently saved language per problem", () => {
  expect(
    examDrafts(
      {
        [draftKey("a", "1001", "java", "e")]: {
          code: "java",
          revisionId: "2001",
          savedAt: 1,
        },
        [draftKey("a", "1001", "python3", "e")]: {
          code: "python",
          revisionId: "2001",
          savedAt: 2,
        },
        [draftKey("b", "1001", "cpp", "e")]: {
          code: "other user",
          revisionId: "2001",
          savedAt: 3,
        },
        [draftKey("a", "1001", "cpp")]: {
          code: "practice",
          revisionId: "2001",
          savedAt: 4,
        },
      },
      "a",
      "e",
    ),
  ).toEqual([
    {
      problemId: "1001",
      language: "python3",
      sourceCode: "python",
      savedAt: 2,
    },
  ]);
});
