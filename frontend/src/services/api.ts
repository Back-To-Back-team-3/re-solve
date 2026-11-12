import type {
  ActiveTimeSlot,
  AdminMember,
  AdminProblemListItem,
  AssignmentDetail,
  AssignmentStatus,
  AssignmentSummary,
  AuthTokenResponse,
  GoalType,
  JoinType,
  JudgeResult,
  Language,
  Member,
  MembershipCreated,
  MembershipStatus,
  ProblemDetail,
  ProblemFilter,
  ProblemListItem,
  ProblemVisibility,
  RunResponse,
  ServerPage,
  SkillLevel,
  StudyDetail,
  StudyMeItem,
  StudyMemberItem,
  StudyRecommendationsPage,
  StudySummary,
  SubmissionHistoryItem,
  SubmissionReceipt,
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
  const bypassUserId = process.env.NEXT_PUBLIC_DEV_BYPASS_USER_ID;
  if (bypassUserId) headers["X-User-Id"] = bypassUserId;

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
    apiFetch<{ diagnosisId: string }>("/api/v1/diagnoses", {
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

// 문제 API (§2.1)
export const problemApi = {
  getDetail: (problemId: string) =>
    apiFetch<ProblemDetail>(`/api/v1/problems/${problemId}`),
  getList: (filter: ProblemFilter = {}) => {
    const q = new URLSearchParams();
    if (filter.keyword) q.set("keyword", filter.keyword);
    if (filter.tagIds) q.set("tagIds", filter.tagIds);
    if (filter.difficulty) q.set("difficulty", filter.difficulty);
    if (filter.solvedStatus) q.set("solvedStatus", filter.solvedStatus);
    if (filter.sort) q.set("sort", filter.sort);
    if (filter.page != null) q.set("page", String(filter.page));
    if (filter.size != null) q.set("size", String(filter.size));
    const qs = q.toString();
    return apiFetch<ServerPage<ProblemListItem>>(
      `/api/v1/problems${qs ? `?${qs}` : ""}`,
    );
  },
};

// 채점 API (§3.1)
export const judgeApi = {
  run: (body: {
    problemId: string;
    problemRevisionId: string;
    language: Language;
    sourceCode: string;
    useSamples: boolean;
    customInputs: { argumentTexts: Record<string, string> }[];
  }) =>
    apiFetch<RunResponse>("/api/v1/runs", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  submit: (body: {
    problemRevisionId: string;
    language: Language;
    sourceCode: string;
  }) =>
    apiFetch<SubmissionReceipt>("/api/v1/submissions", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(body),
    }),
  getStatus: (submissionId: string) =>
    apiFetch<JudgeResult>(`/api/v1/submissions/${submissionId}`),
  getSubmissions: (problemId: string) =>
    apiFetch<ServerPage<SubmissionHistoryItem>>(
      `/api/v1/me/submissions?problemId=${encodeURIComponent(problemId)}&context=PRACTICE`,
    ),
};

// 관리자 문제 API (§2.3)
export const adminProblemApi = {
  getList: (filter: { visibility?: string; page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    if (filter.visibility) q.set("visibility", filter.visibility);
    if (filter.page != null) q.set("page", String(filter.page));
    if (filter.size != null) q.set("size", String(filter.size));
    const qs = q.toString();
    return apiFetch<ServerPage<AdminProblemListItem>>(
      `/api/v1/admin/problems${qs ? `?${qs}` : ""}`,
    );
  },
  publish: (problemId: string, rowVersion: number) =>
    apiFetch<{ visibility: ProblemVisibility; rowVersion: number }>(
      `/api/v1/admin/problems/${problemId}/publication`,
      {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ rowVersion }),
      },
    ),
  unpublish: (problemId: string, rowVersion: number) =>
    apiFetch<{ visibility: ProblemVisibility; rowVersion: number }>(
      `/api/v1/admin/problems/${problemId}/unpublication`,
      {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ rowVersion }),
      },
    ),
  archive: (problemId: string, rowVersion: number, reason: string) =>
    apiFetch<{ visibility: ProblemVisibility; rowVersion: number }>(
      `/api/v1/admin/problems/${problemId}/archive`,
      {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ rowVersion, reason }),
      },
    ),
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

// 스터디 API (§5)
export const studyApi = {
  create: (body: {
    name: string;
    description: string;
    goal: GoalType;
    skillLevel: SkillLevel;
    preferredLanguage: Language;
    activeTimeSlot: ActiveTimeSlot;
    capacity: number;
    joinType: JoinType;
    isAutoReopen: boolean;
  }) =>
    apiFetch<{ studyId: string }>("/api/v1/studies", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  getList: (params: {
    keyword?: string;
    goal?: GoalType;
    skillLevel?: SkillLevel;
    preferredLanguage?: Language;
    activeTimeSlot?: ActiveTimeSlot;
    joinType?: JoinType;
    status?: string;
    sort?: string;
    page?: number;
    size?: number;
  } = {}) => {
    const q = new URLSearchParams();
    if (params.keyword) q.set("keyword", params.keyword);
    if (params.goal) q.set("goal", params.goal);
    if (params.skillLevel) q.set("skillLevel", params.skillLevel);
    if (params.preferredLanguage) q.set("preferredLanguage", params.preferredLanguage);
    if (params.activeTimeSlot) q.set("activeTimeSlot", params.activeTimeSlot);
    if (params.joinType) q.set("joinType", params.joinType);
    if (params.status) q.set("status", params.status);
    if (params.sort) q.set("sort", params.sort);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<ServerPage<StudySummary>>(
      `/api/v1/studies${qs ? `?${qs}` : ""}`,
    );
  },
  getDetail: (studyId: string) =>
    apiFetch<StudyDetail>(`/api/v1/studies/${studyId}`),
  getMyStudies: (params: { status?: MembershipStatus; page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<ServerPage<StudyMeItem>>(`/api/v1/studies/me${qs ? `?${qs}` : ""}`);
  },
  getRecommendations: (params: { page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<StudyRecommendationsPage>(
      `/api/v1/studies/recommendations${qs ? `?${qs}` : ""}`,
    );
  },
  join: (studyId: string) =>
    apiFetch<MembershipCreated>(`/api/v1/studies/${studyId}/memberships`, {
      method: "POST",
    }),
  leave: (studyId: string) =>
    apiFetch<void>(`/api/v1/studies/${studyId}/memberships/me`, {
      method: "DELETE",
    }),
  getMembers: (studyId: string, params: { status?: MembershipStatus; page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<ServerPage<StudyMemberItem>>(
      `/api/v1/studies/${studyId}/memberships${qs ? `?${qs}` : ""}`,
    );
  },
  approveMember: (studyId: string, membershipId: string) =>
    apiFetch<void>(`/api/v1/studies/${studyId}/memberships/${membershipId}`, {
      method: "PATCH",
      body: JSON.stringify({ status: "APPROVED", reason: null }),
    }),
  rejectMember: (studyId: string, membershipId: string, reason?: string) =>
    apiFetch<void>(`/api/v1/studies/${studyId}/memberships/${membershipId}`, {
      method: "PATCH",
      body: JSON.stringify({ status: "REJECTED", reason: reason ?? null }),
    }),
  removeMember: (studyId: string, membershipId: string, reason: string) =>
    apiFetch<void>(`/api/v1/studies/${studyId}/memberships/${membershipId}`, {
      method: "PATCH",
      body: JSON.stringify({ status: "REMOVED", reason }),
    }),
  changeRole: (studyId: string, membershipId: string, role: "MANAGER" | "MEMBER") =>
    apiFetch<void>(`/api/v1/studies/${studyId}/memberships/${membershipId}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    }),
  delegateLeader: (studyId: string, targetMembershipId: string) =>
    apiFetch<void>(`/api/v1/studies/${studyId}/leader-delegation`, {
      method: "POST",
      body: JSON.stringify({ targetMembershipId }),
    }),
  getAssignments: (studyId: string, params: { status?: AssignmentStatus; page?: number; size?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<ServerPage<AssignmentSummary>>(
      `/api/v1/studies/${studyId}/assignments${qs ? `?${qs}` : ""}`,
    );
  },
  getAssignmentDetail: (studyId: string, assignmentId: string) =>
    apiFetch<AssignmentDetail>(
      `/api/v1/studies/${studyId}/assignments/${assignmentId}`,
    ),
};
