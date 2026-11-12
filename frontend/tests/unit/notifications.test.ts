import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { formatRelativeTime } from "../../src/features/notifications";

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
