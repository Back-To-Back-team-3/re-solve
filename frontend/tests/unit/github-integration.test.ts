import { describe, expect, it } from "vitest";
import {
  formatSyncJobStatus,
  getSyncJobBadgeTone,
  SYNC_JOB_STATUS_FILTERS,
} from "../../src/features/account-github";

describe("account-github helpers", () => {
  it("Sync Job 상태별 한글 라벨을 반환한다", () => {
    expect(formatSyncJobStatus("PENDING")).toBe("대기 중");
    expect(formatSyncJobStatus("RUNNING")).toBe("진행 중");
    expect(formatSyncJobStatus("RETRY_WAITING")).toBe("재시도 대기");
    expect(formatSyncJobStatus("RATE_LIMITED")).toBe("호출 제한 대기");
    expect(formatSyncJobStatus("SUCCEEDED")).toBe("커밋 완료");
    expect(formatSyncJobStatus("FAILED")).toBe("실패");
    expect(formatSyncJobStatus("CANCELED")).toBe("취소됨");
  });

  it("Sync Job 상태별 배지 톤을 반환한다", () => {
    expect(getSyncJobBadgeTone("SUCCEEDED")).toBe("ok");
    expect(getSyncJobBadgeTone("FAILED")).toBe("danger");
    expect(getSyncJobBadgeTone("PENDING")).toBe("warn");
    expect(getSyncJobBadgeTone("CANCELED")).toBe("");
  });

  it("상태 필터에 전체(ALL)와 7개 도메인 상태를 포함한다", () => {
    expect(SYNC_JOB_STATUS_FILTERS).toHaveLength(8);
  });
});
