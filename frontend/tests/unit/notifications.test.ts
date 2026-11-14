import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { formatRelativeTime } from "../../src/features/notifications";
import { NOTIFICATION_PREFERENCE_ITEMS } from "../../src/features/account-notifications";

describe("NOTIFICATION_PREFERENCE_ITEMS", () => {
  it("명세 §7.1.6의 6개 수신 설정 필드를 순서대로 포함한다", () => {
    expect(NOTIFICATION_PREFERENCE_ITEMS.map((item) => item.key)).toEqual([
      "isExamContestEnabled",
      "isStudyApplicationEnabled",
      "isAssignmentEnabled",
      "isLevelChangedEnabled",
      "isHintReadyEnabled",
      "isGithubSyncEnabled",
    ]);
  });
});

describe("formatRelativeTime", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("30초 전은 초 단위로 반환한다", () => {
    const now = new Date("2026-01-01T12:00:00Z");
    vi.setSystemTime(now);
    const iso = new Date(now.getTime() - 30_000).toISOString();
    expect(formatRelativeTime(iso)).toContain("초");
  });

  it("5분 전은 분 단위로 반환한다", () => {
    const now = new Date("2026-01-01T12:00:00Z");
    vi.setSystemTime(now);
    const iso = new Date(now.getTime() - 5 * 60_000).toISOString();
    expect(formatRelativeTime(iso)).toContain("분");
  });

  it("3시간 전은 시간 단위로 반환한다", () => {
    const now = new Date("2026-01-01T12:00:00Z");
    vi.setSystemTime(now);
    const iso = new Date(now.getTime() - 3 * 3_600_000).toISOString();
    expect(formatRelativeTime(iso)).toContain("시간");
  });

  it("3일 전은 일 단위로 반환한다", () => {
    const now = new Date("2026-01-01T12:00:00Z");
    vi.setSystemTime(now);
    const iso = new Date(now.getTime() - 3 * 86_400_000).toISOString();
    expect(formatRelativeTime(iso)).toContain("일");
  });
});
