import { describe, it, expect, vi, afterEach } from "vitest";

function relativeDate(iso: string): string {
  const toDay = (d: Date) => Math.floor(d.getTime() / (1000 * 60 * 60 * 24));
  const days = toDay(new Date()) - toDay(new Date(iso));
  if (days === 0) return "오늘";
  if (days === 1) return "어제";
  return `${days}일 전`;
}

describe("relativeDate", () => {
  afterEach(() => vi.useRealTimers());

  it("오늘 날짜 ISO 문자열이면 '오늘'을 반환한다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-14T12:00:00Z"));
    expect(relativeDate("2026-11-14T08:00:00Z")).toBe("오늘");
  });

  it("날짜가 다르면 경과 시간이 24시간 미만이어도 '어제'를 반환한다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-14T12:00:00Z"));
    expect(relativeDate("2026-11-13T12:01:00Z")).toBe("어제");
  });

  it("정확히 24시간 전이면 '어제'를 반환한다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-14T12:00:00Z"));
    expect(relativeDate("2026-11-13T12:00:00Z")).toBe("어제");
  });

  it("2일 전이면 '2일 전'을 반환한다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-14T12:00:00Z"));
    expect(relativeDate("2026-11-12T12:00:00Z")).toBe("2일 전");
  });

  it("7일 전이면 '7일 전'을 반환한다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-14T12:00:00Z"));
    expect(relativeDate("2026-11-07T12:00:00Z")).toBe("7일 전");
  });
});
