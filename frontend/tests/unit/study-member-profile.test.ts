import { describe, expect, it } from "vitest";
import { formatTagLevels, tagNameMap } from "@/features/study-member-profile";

describe("가입 신청자 태그 레벨 표시", () => {
  const names = tagNameMap([
    { id: "1", name: "해시" },
    { id: "2", name: "그래프" },
  ]);

  it("태그 ID 키를 태그 이름으로 바꿔 앞의 두 개만 표시한다", () => {
    expect(formatTagLevels({ "1": "LV2", "2": "LV1", "3": "LV0" }, names)).toBe("해시 LV2 · 그래프 LV1");
  });

  it("이름을 찾지 못한 태그는 태그 ID를 그대로 표시한다", () => {
    expect(formatTagLevels({ "1": "LV1", "9": "LV3" }, names)).toBe("해시 LV1 · 9 LV3");
  });

  it("숫자 태그 ID 키는 응답 순서와 관계없이 ID 오름차순으로 표시한다", () => {
    expect(formatTagLevels({ "9": "LV3", "1": "LV1" }, names)).toBe("해시 LV1 · 9 LV3");
  });

  it("태그 목록이 비어 있으면 모든 태그를 ID로 표시한다", () => {
    expect(formatTagLevels({ "1": "LV2" }, tagNameMap([]))).toBe("1 LV2");
  });

  it("진입한 태그가 없으면 빈 문자열을 돌려준다", () => {
    expect(formatTagLevels({}, names)).toBe("");
  });
});
