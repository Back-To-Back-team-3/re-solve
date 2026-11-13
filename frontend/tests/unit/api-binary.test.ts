import { afterEach, expect, it, vi } from "vitest";
import { adminProblemApi, apiFetch, apiFetchBlob, setAccessTokenSetter, setTokenGetter } from "@/services/api";

afterEach(() => { vi.unstubAllGlobals(); setTokenGetter(() => null); setAccessTokenSetter(() => {}); });
it("이미지는 인증을 유지하고 multipart Content-Type을 브라우저에 맡긴다", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: { imageId: "1" } })));
  vi.stubGlobal("fetch", fetcher); setTokenGetter(() => "access");
  await adminProblemApi.uploadImage(new File(["bytes"], "image.png", { type: "image/png" }), "same-key");
  const init: RequestInit = fetcher.mock.calls[0][1];
  expect(init.body).toBeInstanceOf(FormData);
  expect(new Headers(init.headers).get("Content-Type")).toBeNull();
  expect(new Headers(init.headers).get("Authorization")).toBe("Bearer access");
  expect(new Headers(init.headers).get("Idempotency-Key")).toBe("same-key");
});
it("이미지 미리보기 401도 토큰 갱신 후 같은 주소로 다시 조회한다", async () => {
  let token = "old"; setTokenGetter(() => token); setAccessTokenSetter((value) => { token = value; });
  const fetcher = vi.fn()
    .mockResolvedValueOnce(new Response("", { status: 401 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: "new" } })))
    .mockResolvedValueOnce(new Response("image-bytes", { headers: { "Content-Type": "image/png" } }));
  vi.stubGlobal("fetch", fetcher);
  expect(await (await apiFetchBlob("/image")).text()).toBe("image-bytes");
  expect(fetcher.mock.calls[2][0]).toBe("/image");
  expect(fetcher.mock.calls[2][1].headers.Authorization).toBe("Bearer new");
});
it("HTTP 실패를 성공 JSON으로 오인하지 않는다", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response('{"success":true,"data":{}}', { status: 500 })));
  await expect(apiFetch("/api/v1/admin/problems")).rejects.toMatchObject({ code: "UNKNOWN" });
});
it("JSON 응답이 없는 204 요청도 정상 종료한다", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
  await expect(apiFetch<void>("/api/v1/auth/logout", { method: "POST" })).resolves.toBeUndefined();
});
it.each([{ status: 401, code: "UNAUTHORIZED" }, { status: 503, code: "UNKNOWN" }])(
  "이미지 토큰 갱신의 HTTP $status 오류 분류를 유지한다", async ({ status, code }) => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status }));
    vi.stubGlobal("fetch", fetcher);
    await expect(apiFetchBlob("/image")).rejects.toMatchObject({ code });
  },
);
it("토큰 갱신 후 원래 요청의 권한 오류를 로그인 실패로 바꾸지 않는다", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: "new" } })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ success: false,
      error: { code: "AUTH_ACCESS_DENIED", message: "권한이 없습니다." } }), { status: 403 }));
  vi.stubGlobal("fetch", fetcher);
  await expect(apiFetch("/protected")).rejects.toMatchObject({ code: "AUTH_ACCESS_DENIED" });
});
