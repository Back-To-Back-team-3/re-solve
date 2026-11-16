import { afterEach, expect, it, vi } from "vitest";
import { apiFetch, setAccessTokenSetter, setTokenGetter } from "@/services/api";
import { connectNotificationStream } from "@/services/notification-stream";
const notification = { notificationId: "42", type: "SYSTEM_NOTICE", title: "알림", content: "공지", linkUrl: "/", createdAt: "2026-11-16T20:00:00+09:00" };
let stop = () => {};
afterEach(() => { stop(); vi.useRealTimers(); vi.unstubAllGlobals(); setTokenGetter(() => null); setAccessTokenSetter(() => {}); });
it("Bearer 인증·갱신 후 마지막 이벤트 ID로 재연결한다", async () => {
  vi.useFakeTimers();
  let token = "old"; setTokenGetter(() => token); setAccessTokenSetter((v) => { token = v; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: "new" } })))
    .mockResolvedValueOnce(new Response(`id: 42\r\nevent: notification\r\ndata: ${JSON.stringify(notification)}\r\n\r\n`, { headers: { "Content-Type": "text/event-stream" } }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetcher); const receive = vi.fn(); stop = connectNotificationStream(receive);
  await vi.advanceTimersByTimeAsync(0);
  expect(receive).toHaveBeenCalledWith(notification);
  expect(fetcher.mock.calls[0][1].headers.Authorization).toBe("Bearer old");
  expect(fetcher.mock.calls[2][1].headers.Authorization).toBe("Bearer new");
  await vi.advanceTimersByTimeAsync(3000);
  expect(fetcher.mock.calls[3][1].headers["Last-Event-ID"]).toBe("42");
  await vi.advanceTimersByTimeAsync(30000); expect(fetcher).toHaveBeenCalledTimes(4);
});
it("연결 실패는 재시도하고 로그아웃 시 대기를 취소한다", async () => {
  vi.useFakeTimers(); const fetcher = vi.fn().mockRejectedValue(new TypeError("network"));
  vi.stubGlobal("fetch", fetcher); stop = connectNotificationStream(vi.fn());
  await vi.advanceTimersByTimeAsync(3000); expect(fetcher).toHaveBeenCalledTimes(2);
  stop(); await vi.advanceTimersByTimeAsync(30000); expect(fetcher).toHaveBeenCalledTimes(2);
  expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
});
it("로그아웃 후 늦은 갱신 응답으로 토큰을 복원하지 않는다", async () => {
  let finish!: (response: Response) => void;
  const setter = vi.fn(); setAccessTokenSetter(setter);
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve; }));
  vi.stubGlobal("fetch", fetcher); stop = connectNotificationStream(vi.fn());
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  stop(); finish(new Response(JSON.stringify({ data: { accessToken: "late" } })));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(setter).not.toHaveBeenCalled(); expect(fetcher).toHaveBeenCalledTimes(2);
});
it("갱신 인증 실패는 반복 연결하지 않는다", async () => {
  vi.useFakeTimers(); const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
  vi.stubGlobal("fetch", fetcher); stop = connectNotificationStream(vi.fn());
  await vi.advanceTimersByTimeAsync(30000); expect(fetcher).toHaveBeenCalledTimes(2);
});
it("분할 UTF-8·CRLF와 heartbeat를 처리하고 로그아웃 시 reader를 취소한다", async () => {
  let source!: ReadableStreamDefaultController<Uint8Array>; const cancel = vi.fn();
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(new ReadableStream({ start(c) { source = c; }, cancel }), { headers: { "Content-Type": "text/event-stream" } })));
  const receive = vi.fn(); stop = connectNotificationStream(receive);
  for (const byte of new TextEncoder().encode(`event: heartbeat\r\ndata: {}\r\n\r\nevent: notification\r\ndata: ${JSON.stringify(notification)}\r\n\r\n`)) source.enqueue(Uint8Array.of(byte));
  await vi.waitFor(() => expect(receive).toHaveBeenCalledExactlyOnceWith(notification));
  stop(); await vi.waitFor(() => expect(cancel).toHaveBeenCalledOnce());
});
it("중단된 프레임의 ID는 확정하지 않고 마지막 완료 이벤트부터 재연결한다", async () => {
  vi.useFakeTimers();
  const frame = (id: string) => `id: ${id}\nevent: notification\ndata: ${JSON.stringify({ ...notification, notificationId: id })}\n`;
  const response = (body: string) => new Response(body, { headers: { "Content-Type": "text/event-stream" } });
  const fetcher = vi.fn().mockResolvedValueOnce(response(frame("42") + "\n" + frame("43")))
    .mockResolvedValueOnce(response(frame("43") + "\n"));
  vi.stubGlobal("fetch", fetcher); const receive = vi.fn(); stop = connectNotificationStream(receive);
  await vi.advanceTimersByTimeAsync(3000);
  expect(fetcher.mock.calls[1][1].headers["Last-Event-ID"]).toBe("42");
  expect(receive.mock.calls.map(([event]) => event.notificationId)).toEqual(["42", "43"]);
});
it("일반 API와 SSE가 동시에 401을 받아도 각각 갱신 후 요청을 완료한다", async () => {
  vi.useFakeTimers(); let token = "expired";
  setTokenGetter(() => token); setAccessTokenSetter((value) => { token = value; });
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    if (url.includes("refresh")) return new Response(JSON.stringify({ data: { accessToken: "fresh" } }));
    if (new Headers(init.headers).get("Authorization") === "Bearer expired") return new Response(null, { status: 401 });
    if (url.includes("subscribe")) return new Response(null, { status: 204 });
    return new Response(JSON.stringify({ success: true, data: { unreadCount: 1 } }));
  });
  vi.stubGlobal("fetch", fetcher); stop = connectNotificationStream(vi.fn());
  await expect(apiFetch("/api/v1/notifications/unread-count")).resolves.toEqual({ unreadCount: 1 });
  await vi.advanceTimersByTimeAsync(30000);
  expect(fetcher).toHaveBeenCalledTimes(6);
});
it("잘못된 이벤트 데이터는 화면 콜백에 전달하지 않는다", async () => {
  vi.useFakeTimers(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response('event: notification\ndata: null\n\nevent: notification\ndata: {"title":"알림"}\n\n', { headers: { "Content-Type": "text/event-stream" } })));
  const receive = vi.fn(); stop = connectNotificationStream(receive);
  await vi.advanceTimersByTimeAsync(0); expect(receive).not.toHaveBeenCalled();
});
