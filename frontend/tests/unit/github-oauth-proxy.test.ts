import { afterEach, expect, it, vi } from "vitest";
import { GET as start } from "@/app/api/v1/auth/github/route";
import { GET as callback } from "@/app/api/v1/auth/github/callback/route";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it.each([
  [start, "/api/v1/auth/github?redirectPath=%2Fmypage", "https://github.com/login/oauth/authorize"],
  [callback, "/api/v1/auth/github/callback?code=example&state=example", "https://frontend.example/auth/callback"],
])("로그인 요청의 리다이렉트와 쿠키를 전달한다", async (handler, path, location) => {
  vi.stubEnv("API_URL", "https://api.example");
  const upstreamHeaders = new Headers({ Location: location });
  const cookies = ["nonce=; Max-Age=0; HttpOnly", "refresh=example; HttpOnly"];
  for (const cookie of cookies) upstreamHeaders.append("Set-Cookie", cookie);
  const fetchUpstream = vi.fn().mockResolvedValue(new Response(null, {
    status: 302,
    headers: upstreamHeaders,
  }));
  vi.stubGlobal("fetch", fetchUpstream);

  const headers = new Headers({ Cookie: "nonce=example" });
  const response = await handler(new Request(`https://frontend.example${path}`, { headers }));

  expect(fetchUpstream).toHaveBeenCalledWith(new URL(`https://api.example${path}`), {
    headers,
    redirect: "manual",
    cache: "no-store",
  });
  expect(response.status).toBe(302);
  expect(response.headers.get("Location")).toBe(location);
  expect(response.headers.getSetCookie()).toEqual(cookies);
});

it.each([
  [start, "https://api.example/backend", "/api/v1/auth/github?redirectPath=%2Fmypage"],
  [callback, "https://api.example/backend/", "/api/v1/auth/github/callback?code=example&state=example"],
])("로그인 요청을 전달할 때 API 주소의 경로 접두사를 유지한다", async (handler, apiUrl, path) => {
  vi.stubEnv("API_URL", apiUrl);
  const fetchUpstream = vi.fn().mockResolvedValue(new Response(null, { status: 302 }));
  vi.stubGlobal("fetch", fetchUpstream);

  await handler(new Request(`https://frontend.example${path}`));

  expect(fetchUpstream.mock.calls[0][0]).toEqual(new URL(`https://api.example/backend${path}`));
});
