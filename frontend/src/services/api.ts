export type ApiError = { code: string; message: string };

type ApiResponse<T> =
  | { success: true; data: T; message?: string }
  | { success: false; error: ApiError };

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      credentials: "include",
      headers: { "Content-Type": "application/json", ...(init?.headers as Record<string, string> | undefined) },
    });
  } catch (e) {
    throw {
      code: "UNKNOWN",
      message: e instanceof Error ? e.message : "네트워크 오류",
    } satisfies ApiError;
  }

  let json: ApiResponse<T>;
  try {
    json = await res.json();
  } catch {
    // 게이트웨이 오류(502/504) 등 JSON이 아닌 응답 본문 처리
    throw { code: "UNKNOWN", message: `HTTP ${res.status}` } satisfies ApiError;
  }
  if (!json.success) throw json.error;
  return json.data;
}
