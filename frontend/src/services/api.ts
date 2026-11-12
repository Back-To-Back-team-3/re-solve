import type {
  AdminMember,
  AuthTokenResponse,
  Member,
  TagLevel,
  UserRole,
  UserStatus,
} from "./types";

export type ApiError = { code: string; message: string };

type ApiResponse<T> =
  | { success: true; data: T; message?: string }
  | { success: false; error: ApiError };

// WorkspaceProvider가 mount 시 등록해 apiFetch가 최신 토큰을 주입할 수 있도록 한다.
let tokenGetter: (() => string | null) | null = null;
export function setTokenGetter(fn: () => string | null) {
  tokenGetter = fn;
}

// 401 인터셉터가 갱신된 토큰을 store에 반영하기 위해 WorkspaceProvider가 등록한다.
let accessTokenSetter: ((token: string) => void) | null = null;
export function setAccessTokenSetter(fn: (token: string) => void) {
  accessTokenSetter = fn;
}

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  return apiFetchInner<T>(path, init, false);
}

async function apiFetchInner<T>(
  path: string,
  init: RequestInit | undefined,
  isRetry: boolean,
): Promise<T> {
  const token = tokenGetter?.();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  let res: Response;
  try {
    res = await fetch(path, { ...init, credentials: "include", headers });
  } catch (e) {
    throw {
      code: "UNKNOWN",
      message: e instanceof Error ? e.message : "네트워크 오류",
    } satisfies ApiError;
  }

  // /auth/token/refresh 자체는 인터셉터에서 제외해 무한 루프를 방지한다.
  if (res.status === 401 && !isRetry && !path.includes("/auth/token/refresh")) {
    try {
      const refreshRes = await fetch("/api/v1/auth/token/refresh", {
        method: "POST",
        credentials: "include",
      });
      if (refreshRes.ok) {
        const refreshJson = await refreshRes.json();
        const newToken: string | undefined = refreshJson.data?.accessToken;
        if (!newToken)
          throw { code: "UNAUTHORIZED", message: "토큰 갱신에 실패했습니다" } satisfies ApiError;
        accessTokenSetter?.(newToken);
        return apiFetchInner<T>(path, init, true);
      }
    } catch {}
    throw { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } satisfies ApiError;
  }

  let json: ApiResponse<T>;
  try {
    json = await res.json();
  } catch {
    // 게이트웨이 오류(502/504) 등 JSON이 아닌 응답 본문을 처리한다.
    throw { code: "UNKNOWN", message: `HTTP ${res.status}` } satisfies ApiError;
  }
  if (!json.success) throw json.error;
  return json.data;
}

// 인증 API (§1.1)
export const authApi = {
  exchangeToken: (loginCode: string) =>
    apiFetch<AuthTokenResponse>("/api/v1/auth/token", {
      method: "POST",
      body: JSON.stringify({ loginCode }),
    }),
  refresh: () =>
    apiFetch<{ accessToken: string; expiresIn: number }>(
      "/api/v1/auth/token/refresh",
      { method: "POST" },
    ),
  logout: () =>
    apiFetch<void>("/api/v1/auth/logout", { method: "POST" }),
};

// 회원 API (§1.2·§1.4·§1.5)
export const memberApi = {
  getMe: () => apiFetch<Member>("/api/v1/members/me"),
  updateMe: (body: { nickname: string }) =>
    apiFetch<Member>("/api/v1/members/me", {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
  getTagLevels: () =>
    apiFetch<{ content: TagLevel[] }>("/api/v1/members/me/tag-levels"),
  startDiagnosis: (tagId: string) =>
    apiFetch<{ diagnosisId: string }>("/api/v1/members/me/diagnoses", {
      method: "POST",
      body: JSON.stringify({ tagId }),
    }),
};

// 문제 태그 API — B 도메인 §2.5.1 (SkillPage 태그 이름 표시용)
export const problemTagApi = {
  getTags: () =>
    apiFetch<{
      content: { tagId: string; name: string; displayOrder: number }[];
    }>("/api/v1/problems/tags"),
};

// 관리자 API (§1.6)
export const adminApi = {
  getMembers: (params: {
    keyword?: string;
    status?: UserStatus | "";
    role?: UserRole | "";
    page?: number;
    size?: number;
  }) => {
    const q = new URLSearchParams();
    if (params.keyword) q.set("keyword", params.keyword);
    if (params.status) q.set("status", params.status);
    if (params.role) q.set("role", params.role);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<{
      content: AdminMember[];
      page: number;
      size: number;
      totalElements: number;
      totalPages: number;
      hasNext: boolean;
    }>(`/api/v1/admin/members${qs ? `?${qs}` : ""}`);
  },
  updateStatus: (memberId: string, status: "ACTIVE" | "SUSPENDED", reason: string) =>
    apiFetch<{ memberId: string; status: UserStatus }>(
      `/api/v1/admin/members/${memberId}/status`,
      { method: "PATCH", body: JSON.stringify({ status, reason }) },
    ),
  updateRole: (memberId: string, role: UserRole, reason: string) =>
    apiFetch<{ memberId: string; role: UserRole }>(
      `/api/v1/admin/members/${memberId}/role`,
      { method: "PATCH", body: JSON.stringify({ role, reason }) },
    ),
};
