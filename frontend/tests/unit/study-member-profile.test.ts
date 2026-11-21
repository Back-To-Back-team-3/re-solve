import { describe, expect, it } from "vitest";
import { formatTagLevels, tagNameMap } from "@/features/study-member-profile";

describe("가입 신청자 태그 레벨 표시", () => {
  // 카탈로그는 태그 목록을 표시 순서대로 받는다. 표시 순서가 태그 ID 순서와 다르게 둔다.
  const names = tagNameMap([
    { id: "5", name: "그래프" },
    { id: "1", name: "해시" },
    { id: "3", name: "정렬" },
  ]);

  it("태그 ID 키를 태그 이름으로 바꿔 카탈로그 표시 순서대로 앞의 두 개만 표시한다", () => {
    expect(formatTagLevels({ "1": "LV2", "3": "LV0", "5": "LV1" }, names)).toBe("그래프 LV1 · 해시 LV2");
  });

  it("카탈로그에 없는 태그는 카탈로그 태그 뒤에 태그 ID 그대로 표시한다", () => {
    expect(formatTagLevels({ "9": "LV3", "3": "LV1" }, names)).toBe("정렬 LV1 · 9 LV3");
  });

  it("카탈로그에 없는 태그끼리는 태그 ID 오름차순으로 표시한다", () => {
    expect(formatTagLevels({ "12": "LV1", "9": "LV3" }, names)).toBe("9 LV3 · 12 LV1");
  });

  it("태그 목록이 비어 있으면 모든 태그를 ID로 표시한다", () => {
    expect(formatTagLevels({ "1": "LV2" }, tagNameMap([]))).toBe("1 LV2");
  });

  it("진입한 태그가 없으면 빈 문자열을 돌려준다", () => {
    expect(formatTagLevels({}, names)).toBe("");
  });
});
