import { afterEach, expect, it, vi } from "vitest";
import { contestApi, judgeApi, setAccessTokenSetter, setTokenGetter } from "@/services/api";
import { cancelSubmissionRequests, submissionResponseTimeoutMs } from "@/services/submission-transport";

const body = { problemId: "1001", problemRevisionId: "2001", language: "java" as const, sourceCode: "// original A" };
const receipt = { submissionId: "99", status: "QUEUED" };
afterEach(() => {
  cancelSubmissionRequests(); vi.unstubAllGlobals(); vi.useRealTimers();
  setTokenGetter(() => null); setAccessTokenSetter(() => {});
});

it.each([
  { name: "일반 제출", send: () => judgeApi.submit(body, "original-key") },
  { name: "시험 제출", send: () => contestApi.submitExam("42", { problemId: "1001", language: "java", sourceCode: body.sourceCode, seq: "7" }) },
])("$name: 로그아웃 뒤 도착한 401을 새 계정으로 재전송하지 않는다", async ({ send }) => {
  let token: string | null = "account-A"; setTokenGetter(() => token);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  // 취소 신호를 무시하고 늦은 응답을 돌려주는 서버 경합도 방어해야 한다.
  const fetcher = vi.fn(async () => { await held; return new Response(null, { status: 401 }); });
  vi.stubGlobal("fetch", fetcher);
  const pending = send();
  expect(fetcher).toHaveBeenCalledTimes(1);
  cancelSubmissionRequests(); token = null; token = "account-B"; release();
  await expect(pending).rejects.toBeDefined();
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("갱신 중 로그아웃하면 토큰을 덮거나 원본 제출을 재전송하지 않는다", async () => {
  let token = "A"; setTokenGetter(() => token);
  const setter = vi.fn((value: string) => { token = value; }); setAccessTokenSetter(setter);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockImplementationOnce(async () => { await held; return Response.json({ data: { accessToken: "late-A" } }); });
  vi.stubGlobal("fetch", fetcher);
  const pending = judgeApi.submit(body, "original-key");
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  cancelSubmissionRequests(); token = "B"; release();
  await expect(pending).rejects.toBeDefined();
  expect(setter).not.toHaveBeenCalled(); expect(token).toBe("B"); expect(fetcher).toHaveBeenCalledTimes(2);
});

it("같은 세션의 만료 토큰은 갱신하고 같은 코드·키로 한 번 재시도한다", async () => {
  let token = "old"; setTokenGetter(() => token); setAccessTokenSetter((value) => { token = value; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(Response.json({ data: { accessToken: "new" } }))
    .mockResolvedValueOnce(Response.json({ success: true, data: receipt }, { status: 202 }));
  vi.stubGlobal("fetch", fetcher);
  await expect(judgeApi.submit(body, "original-key")).resolves.toEqual(receipt);
  const original: RequestInit = fetcher.mock.calls[0][1], retry: RequestInit = fetcher.mock.calls[2][1];
  expect(new Headers(original.headers).get("Authorization")).toBe("Bearer old");
  expect(new Headers(retry.headers).get("Authorization")).toBe("Bearer new");
  expect(new Headers(retry.headers).get("Idempotency-Key")).toBe("original-key");
  expect(retry.body).toBe(original.body); expect(retry.signal).toBe(original.signal);
  expect(fetcher.mock.calls[1][1].signal).not.toBe(original.signal);
});

it("제출 응답 대기 제한 시간이 지나면 실제 요청을 취소하고 재요청할 수 있다", async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | null | undefined;
  const waiting = (_path: string, init: RequestInit) => {
    signal = init.signal;
    return new Promise<Response>((_resolve, reject) => signal?.addEventListener("abort", () => reject(signal?.reason), { once: true }));
  };
  const fetcher = vi.fn();
  fetcher.mockImplementationOnce(waiting); vi.stubGlobal("fetch", fetcher);
  const pending = expect(judgeApi.submit(body, "original-key")).rejects.toThrow("제출 접수 여부를 확인하지 못했습니다");
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(submissionResponseTimeoutMs - 1);
  expect(signal?.aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(1); await pending;
  expect(signal?.aborted).toBe(true);
  fetcher.mockResolvedValueOnce(Response.json({ success: true, data: receipt }, { status: 202 }));
  await expect(judgeApi.submit(body, "original-key")).resolves.toEqual(receipt);
  const retry: RequestInit = fetcher.mock.calls.at(-1)![1];
  expect(new Headers(retry.headers).get("Idempotency-Key")).toBe("original-key");
  expect(retry.body).toBe(JSON.stringify(body)); expect(retry.signal?.aborted).toBe(false);
});


it.each([false, true])("제출 기한 후에도 갱신은 완료하고 재전송하지 않으며 로그아웃은 갱신을 취소한다 (로그아웃=%s)", async (logout) => {
  vi.useFakeTimers();
  const setter = vi.fn(); setAccessTokenSetter(setter);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockImplementationOnce(async () => { await held; return Response.json({ data: { accessToken: "rotated" } }); });
  vi.stubGlobal("fetch", fetcher);
  const pending = expect(judgeApi.submit(body, "original-key")).rejects.toThrow("제출 접수 여부를 확인하지 못했습니다");
  await vi.advanceTimersByTimeAsync(0);
  const request: RequestInit = fetcher.mock.calls[0][1], refresh: RequestInit = fetcher.mock.calls[1][1];
  await vi.advanceTimersByTimeAsync(submissionResponseTimeoutMs);
  expect(request.signal?.aborted).toBe(true); expect(refresh.signal?.aborted).toBe(false);
  if (logout) cancelSubmissionRequests();
  expect(refresh.signal?.aborted).toBe(logout);
  release(); await pending;
  expect(setter.mock.calls).toEqual(logout ? [] : [["rotated"]]);
  expect(fetcher).toHaveBeenCalledTimes(2);
});
