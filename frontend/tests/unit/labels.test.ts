import { describe, expect, it } from "vitest";
import {
  judgeStatusLabel,
  labelOf,
  languageLabel,
  solvedStatusLabel,
  verdictLabel,
} from "../../src/services/labels";

describe("labelOf", () => {
  it("명세 값이면 표시 문구를 반환한다", () => {
    expect(labelOf(verdictLabel, "AC")).toBe("정답");
    expect(labelOf(judgeStatusLabel, "JUDGING")).toBe("채점 중");
    expect(labelOf(judgeStatusLabel, "RETRY_WAITING")).toBe("재시도 대기");
    expect(labelOf(solvedStatusLabel, "UNSOLVED")).toBe("미해결");
    expect(labelOf(languageLabel, "python3")).toBe("Python");
  });

  it("테스트별 시스템 실패 판정도 표시한다", () => {
    expect(labelOf(verdictLabel, "SYSTEM_ERROR")).toBe("시스템 오류");
  });

  it("명세에 없는 값이면 빈칸 대신 원래 값을 반환한다", () => {
    expect(labelOf(judgeStatusLabel, "UNKNOWN_STATE")).toBe("UNKNOWN_STATE");
  });
});
