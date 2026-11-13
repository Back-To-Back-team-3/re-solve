import { describe, it, expect, vi, afterEach } from "vitest";
import { relativeDate } from "@/features/home";

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

  it("KST 자정 직후(00:10 KST)에 전날 23:50 KST 제출은 '어제'를 반환한다", () => {
    // Now  = 2026-11-14T00:10 KST = 2026-11-13T15:10Z
    // Post = 2026-11-13T23:50 KST = 2026-11-13T14:50Z
    // UTC 차이 20분 미만이지만 KST 로컬 날짜 기준 다른 날 → "어제"
    vi.useFakeTimers({ timezone: "Asia/Seoul" });
    vi.setSystemTime(new Date("2026-11-13T15:10:00Z"));
    expect(relativeDate("2026-11-13T14:50:00Z")).toBe("어제");
  });
});
