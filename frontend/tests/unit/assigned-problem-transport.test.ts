import { afterEach, expect, it, vi } from "vitest";
import { studyApi, setTokenGetter, setAccessTokenSetter } from "@/services/api";
import { cancelAssignedProblemRequests } from "@/services/assigned-problem-transport";

const assigned = { problemId: "1001", assigned: true };
const json = (data = assigned) => Response.json({ success: true, data });
afterEach(() => {
  cancelAssignedProblemRequests(); vi.unstubAllGlobals();
  setTokenGetter(() => null); setAccessTokenSetter(() => {});
});

it("화면을 떠난 조회의 늦은 401은 인증 갱신을 시작하지 않는다", async () => {
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  const fetcher = vi.fn(async () => { await held; return new Response(null, { status: 401 }); });
  vi.stubGlobal("fetch", fetcher);
  const query = new AbortController();
  const pending = expect(studyApi.getAssignedProblem("1001", query.signal)).rejects.toBeDefined();
  query.abort(); release(); await pending;
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("같은 문제에서 회원이 바뀌면 이전 회원의 늦은 본문을 새 회원의 결과로 반환하지 않는다", async () => {
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let token = "member-A", bodyStarted = false; setTokenGetter(() => token);
  const oldResponse = json();
  oldResponse.json = async () => { bodyStarted = true; await held; return { success: true, data: assigned }; };
  const fetcher = vi.fn().mockResolvedValueOnce(oldResponse).mockResolvedValueOnce(json({ ...assigned, assigned: false }));
  vi.stubGlobal("fetch", fetcher);
  const oldQuery = new AbortController();
  const previous = expect(studyApi.getAssignedProblem("1001", oldQuery.signal)).rejects.toBeDefined();
  await vi.waitFor(() => expect(bodyStarted).toBe(true));
  cancelAssignedProblemRequests(); token = "member-B";
  const current = await studyApi.getAssignedProblem("1001", new AbortController().signal);
  expect(current).toEqual({ ...assigned, assigned: false });
  release(); await previous;
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(new Headers(fetcher.mock.calls[0][1].headers).get("Authorization")).toBe("Bearer member-A");
  expect(new Headers(fetcher.mock.calls[1][1].headers).get("Authorization")).toBe("Bearer member-B");
});

it("대기 기한 뒤 완료된 JSON 본문으로 배정을 허용하지 않는다", async () => {
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let bodyStarted = false;
  const response = json();
  response.json = async () => { bodyStarted = true; await held; return { success: true, data: assigned }; };
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
  const query = new AbortController();
  const pending = expect(studyApi.getAssignedProblem("1001", query.signal)).rejects.toBeDefined();
  await vi.waitFor(() => expect(bodyStarted).toBe(true));
  query.abort(); release(); await pending;
});

it.each([false, true])("진행 중 갱신은 조회 취소와 분리하고 로그아웃에서는 취소한다 (로그아웃=%s)", async (logout) => {
  const setter = vi.fn(); setAccessTokenSetter(setter);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockImplementationOnce(async () => { await held; return Response.json({ data: { accessToken: "rotated" } }); });
  vi.stubGlobal("fetch", fetcher);
  const query = new AbortController();
  const pending = expect(studyApi.getAssignedProblem("1001", query.signal)).rejects.toBeDefined();
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  const refresh: RequestInit = fetcher.mock.calls[1][1];
  query.abort(); expect(refresh.signal?.aborted).toBe(false);
  if (logout) cancelAssignedProblemRequests();
  expect(refresh.signal?.aborted).toBe(logout);
  release(); await pending;
  expect(setter.mock.calls).toEqual(logout ? [] : [["rotated"]]);
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it("현재 조회의 401은 갱신 후 같은 GET으로 재시도한다", async () => {
  let token = "old"; setTokenGetter(() => token); setAccessTokenSetter((value) => { token = value; });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(Response.json({ data: { accessToken: "new" } })).mockResolvedValueOnce(json());
  vi.stubGlobal("fetch", fetcher);
  await expect(studyApi.getAssignedProblem("1001", new AbortController().signal)).resolves.toEqual(assigned);
  expect(fetcher).toHaveBeenCalledTimes(3);
  expect(fetcher.mock.calls[2][0]).toBe(fetcher.mock.calls[0][0]);
  expect(new Headers(fetcher.mock.calls[2][1].headers).get("Authorization")).toBe("Bearer new");
});
