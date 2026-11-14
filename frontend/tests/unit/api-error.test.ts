import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ACTION_FAILED_MESSAGE,
  NETWORK_ERROR_MESSAGE,
  SERVER_ERROR_MESSAGE,
  actionErrorMessage,
  apiFetch,
  httpErrorMessage,
  isApiError,
  rethrowWithMessage,
} from "../../src/services/api";

afterEach(() => vi.unstubAllGlobals());

describe("httpErrorMessage", () => {
  it("5xx이면 사용자용 일시 오류 문구를 반환한다", () => {
    expect(httpErrorMessage(500)).toBe(SERVER_ERROR_MESSAGE);
    expect(httpErrorMessage(503)).toBe(SERVER_ERROR_MESSAGE);
  });

  it("4xx이면 상태 코드를 그대로 표시한다", () => {
    expect(httpErrorMessage(404)).toBe("HTTP 404");
  });
});

describe("actionErrorMessage", () => {
  it("서버 오류 객체이면 서버 메시지를 그대로 반환한다", () => {
    expect(actionErrorMessage({ code: "STUDY_FULL", message: "정원이 가득 찼습니다." }))
      .toBe("정원이 가득 찼습니다.");
  });

  it("Error 객체이면 그 메시지를 반환한다", () => {
    expect(actionErrorMessage(new Error("최대 5개 스터디에 참여할 수 있습니다."))).toBe("최대 5개 스터디에 참여할 수 있습니다.");
  });

  it("알 수 없는 예외이면 기본 문구를 반환한다", () => {
    expect(actionErrorMessage("boom")).toBe(ACTION_FAILED_MESSAGE);
    expect(actionErrorMessage(null)).toBe(ACTION_FAILED_MESSAGE);
  });
});

describe("isApiError", () => {
  it("code와 message가 문자열인 객체만 서버 오류로 판정한다", () => {
    expect(isApiError({ code: "UNAUTHORIZED", message: "로그인이 필요합니다" })).toBe(true);
    expect(isApiError({ code: 401 })).toBe(false);
    expect(isApiError(undefined)).toBe(false);
  });
});

describe("rethrowWithMessage", () => {
  it("화면 문구가 정해진 오류 코드이면 그 문구의 Error로 바꿔 던진다", () => {
    expect(() => rethrowWithMessage({ code: "STUDY_FULL", message: "server" }, { STUDY_FULL: "정원이 가득 찼습니다." }))
      .toThrow(new Error("정원이 가득 찼습니다."));
  });

  it("세션 만료처럼 문구가 없는 오류는 코드를 유지한 채 그대로 던진다", () => {
    const expired = { code: "UNAUTHORIZED", message: "로그인이 필요합니다" };
    let thrown: unknown;
    try {
      rethrowWithMessage(expired, { STUDY_FULL: "정원이 가득 찼습니다." });
    } catch (e) {
      thrown = e;
    }
    expect(thrown).toBe(expired);
  });
});

describe("apiFetch 오류 문구", () => {
  it("네트워크 실패이면 브라우저 원문 대신 연결 실패 문구를 던진다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await expect(apiFetch("/api/v1/problems")).rejects.toEqual({ code: "UNKNOWN", message: NETWORK_ERROR_MESSAGE });
  });

  it("본문이 명세 형식이 아닌 5xx이면 일시 오류 문구를 던진다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>Bad Gateway</html>", { status: 502 })));
    await expect(apiFetch("/api/v1/problems")).rejects.toEqual({ code: "UNKNOWN", message: SERVER_ERROR_MESSAGE });
  });

  it("명세 형식의 오류 응답이면 서버 코드와 메시지를 그대로 던진다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ success: false, error: { code: "STUDY_ALREADY_APPLIED", message: "이미 신청한 스터디입니다." } }),
      { status: 409 },
    )));
    await expect(apiFetch("/api/v1/studies/1/memberships", { method: "POST" }))
      .rejects.toEqual({ code: "STUDY_ALREADY_APPLIED", message: "이미 신청한 스터디입니다." });
  });
});
