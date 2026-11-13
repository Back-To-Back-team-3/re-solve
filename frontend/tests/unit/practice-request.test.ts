import { describe, expect, it } from "vitest";
import { isPracticeRequestExpired, PRACTICE_REQUEST_TTL_MS } from "@/services/practice-request";

describe("일반 제출 재요청 보장 기간", () => {
  it("최초 요청부터 24시간 직전까지 재요청을 허용한다", () => {
    expect(isPracticeRequestExpired(1000, 1000 + PRACTICE_REQUEST_TTL_MS - 1)).toBe(false);
  });
  it("정확히 24시간이 지난 시점부터 만료로 처리한다", () => {
    expect(isPracticeRequestExpired(1000, 1000 + PRACTICE_REQUEST_TTL_MS)).toBe(true);
  });
  it("24시간을 초과한 요청도 만료 상태를 유지한다", () => {
    expect(isPracticeRequestExpired(1000, 1000 + PRACTICE_REQUEST_TTL_MS + 1)).toBe(true);
  });
});
