/** GitHub 로그인 시작·콜백의 리다이렉트와 쿠키를 브라우저에 전달한다. (API 명세서 §1.1.1·§1.1.2) */
export async function forwardGitHubOAuth(request: Request): Promise<Response> {
  const { pathname, search } = new URL(request.url);
  const apiUrl = process.env.API_URL ?? "http://localhost:8080";
  const headers = new Headers();
  const cookie = request.headers.get("Cookie");
  if (cookie) headers.set("Cookie", cookie);

  const upstream = await fetch(new URL(pathname + search, apiUrl), {
    headers,
    redirect: "manual",
    cache: "no-store",
  });

  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  for (const name of ["Location", "Content-Type"]) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  for (const value of upstream.headers.getSetCookie()) {
    responseHeaders.append("Set-Cookie", value);
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}
