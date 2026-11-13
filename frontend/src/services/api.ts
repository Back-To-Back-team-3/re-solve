import type {
  ActiveTimeSlot,
  AdminMember,
  AdminProblemListItem,
  AssignmentDetail,
  AssignmentStatus,
  AssignmentSummary,
  AuthTokenResponse,
  CreateExamRequest,
  DraftSaveRequest,
  DraftSaveResponse,
  ExamCreated,
  ExamDetail,
  ExamFinishResponse,
  ExamProblemsResponse,
  ExamStartResponse,
  ExamSubmitRequest,
  ExamSubmitResponse,
  ExamSummary,
  GithubConnectionInfo,
  GithubInstallRequest,
  GithubInstallResult,
  GithubInstallUrlResponse,
  GithubManualSyncResponse,
  GithubSettingsResult,
  GithubSyncJobItem,
  GithubSyncJobStatus,
  GoalType,
  JoinType,
  JudgeResult,
  JudgeLanguage,
  Language,
  MarkAllReadResponse,
  MarkReadResponse,
  Member,
  MemberUpdateResponse,
  MembershipCreated,
  MembershipStatus,
  MyParticipation,
  MyResultResponse,
  NotificationListResponse,
  NotificationPreferences,
  ProblemDetail,
  ProblemFilter,
  ProblemListItem,
  ProblemVisibility,
  RankingResponse,
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
  SubmissionCreateRequest,
  TagLevel,
  UnreadCountResponse,
  UpdateNotificationPreferencesRequest,
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

// useAction 등 훅 바깥에서 현재 로그인 회원을 읽기 위해 WorkspaceProvider가 등록한다.
let memberGetter: (() => Member | null) | null = null;
export function setMemberGetter(fn: () => Member | null) {
  memberGetter = fn;
}
export function getMember(): Member | null {
  return memberGetter?.() ?? null;
}

// 앱 시작 시 인증 복원이 끝나기 전에 useAction이 비로그인으로 오판하지 않도록 완료 시점을 공유한다.
let resolveAuthReady: () => void = () => {};
const authReady = new Promise<void>((resolve) => {
  resolveAuthReady = resolve;
});
export function markAuthReady() {
  resolveAuthReady();
}
export function whenAuthReady(): Promise<void> {
  return authReady;
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
    let refreshRes: Response;
    try {
      refreshRes = await fetch("/api/v1/auth/token/refresh", {
        method: "POST",
        credentials: "include",
      });
    } catch (e) {
      throw {
        code: "UNKNOWN",
        message: e instanceof Error ? e.message : "네트워크 오류",
      } satisfies ApiError;
    }
    // 명세상 갱신 인증 실패는 401뿐이다. 그 외 실패는 세션이 남아 있을 수 있어 UNAUTHORIZED로 바꾸지 않는다.
    if (refreshRes.status === 401)
      throw { code: "UNAUTHORIZED", message: "로그인이 필요합니다" } satisfies ApiError;
    if (!refreshRes.ok)
      throw { code: "UNKNOWN", message: `HTTP ${refreshRes.status}` } satisfies ApiError;
    const refreshJson = await refreshRes.json().catch(() => null);
    const newToken: string | undefined = refreshJson?.data?.accessToken;
    if (!newToken)
      throw {
        code: "UNKNOWN",
        message: "토큰 갱신 응답을 확인하지 못했습니다",
      } satisfies ApiError;
    accessTokenSetter?.(newToken);
    return apiFetchInner<T>(path, init, true);
  }

  if (res.status === 204) {
    return undefined as T;
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
    apiFetch<MemberUpdateResponse>("/api/v1/members/me", {
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
  getLanguages: () =>
    apiFetch<{ content: JudgeLanguage[] }>("/api/v1/judge/languages"),
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
  submit: (body: SubmissionCreateRequest, idempotencyKey: string) =>
    apiFetch<SubmissionReceipt>("/api/v1/submissions", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    }),
  getStatus: (submissionId: string, signal?: AbortSignal) =>
    apiFetch<JudgeResult>(`/api/v1/submissions/${submissionId}`, { signal }),
  getSubmissions: (problemId: string) =>
    apiFetch<ServerPage<SubmissionHistoryItem>>(
      `/api/v1/me/submissions?problemId=${encodeURIComponent(problemId)}&context=PRACTICE`,
    ),
  getMySubmissions: (page: number, size: number) =>
    apiFetch<ServerPage<SubmissionHistoryItem>>(
      `/api/v1/me/submissions?page=${page}&size=${size}`,
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

// 모의 코테·시험 API (§4)
export const contestApi = {
  getExamList: (studyId: string, page = 0, size = 20, status?: string) =>
    apiFetch<ServerPage<ExamSummary>>(
      `/api/v1/studies/${studyId}/exams?page=${page}&size=${size}${status && status !== "all" ? `&status=${status}` : ""}`,
    ),

  createExam: (studyId: string, body: CreateExamRequest) =>
    apiFetch<ExamCreated>(`/api/v1/studies/${studyId}/exams`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  getExamDetail: (examId: string) =>
    apiFetch<ExamDetail>(`/api/v1/exams/${examId}`),

  registerParticipant: (examId: string) =>
    apiFetch<MyParticipation>(`/api/v1/exams/${examId}/participants`, {
      method: "POST",
    }),

  cancelParticipant: (examId: string) =>
    apiFetch<void>(`/api/v1/exams/${examId}/participants/me/cancellation`, {
      method: "POST",
    }),

  startExam: (examId: string) =>
    apiFetch<ExamStartResponse>(`/api/v1/exams/${examId}/start`, {
      method: "POST",
    }),

  getExamProblems: (examId: string) =>
    apiFetch<ExamProblemsResponse>(`/api/v1/exams/${examId}/problems`),

  saveDraft: (examId: string, problemId: string, body: DraftSaveRequest) =>
    apiFetch<DraftSaveResponse>(`/api/v1/exams/${examId}/drafts/${problemId}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  submitExam: (examId: string, body: ExamSubmitRequest) =>
    apiFetch<ExamSubmitResponse>(`/api/v1/exams/${examId}/submissions`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  finishExam: (examId: string) =>
    apiFetch<ExamFinishResponse>(`/api/v1/exams/${examId}/finish`, {
      method: "POST",
    }),

  getMyResult: (examId: string) =>
    apiFetch<MyResultResponse>(`/api/v1/exams/${examId}/results/me`),

  getRanking: (examId: string, after?: string) => {
    const query = after ? `?after=${encodeURIComponent(after)}` : "";
    return apiFetch<RankingResponse>(`/api/v1/exams/${examId}/results${query}`);
  },
};

// GitHub 연동 API (§8)
export const integrationApi = {
  getGithubConnection: () =>
    apiFetch<GithubConnectionInfo>("/api/v1/integrations/github"),

  getGithubInstallUrl: () =>
    apiFetch<GithubInstallUrlResponse>("/api/v1/integrations/github/install-url"),

  installGithub: (body: GithubInstallRequest) =>
    apiFetch<GithubInstallResult>("/api/v1/integrations/github/install", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  updateGithubSettings: (body: { isAutoCommitEnabled: boolean }) =>
    apiFetch<GithubSettingsResult>("/api/v1/integrations/github", {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  disconnectGithub: () =>
    apiFetch<void>("/api/v1/integrations/github", {
      method: "DELETE",
    }),

  getSyncJobs: (
    params: { status?: GithubSyncJobStatus; page?: number; size?: number } = {},
  ) => {
    const q = new URLSearchParams();
    if (params.status) q.set("status", params.status);
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    const qs = q.toString();
    return apiFetch<ServerPage<GithubSyncJobItem>>(
      `/api/v1/integrations/github/sync-jobs${qs ? `?${qs}` : ""}`,
    );
  },

  requestManualSync: (submissionId: string) =>
    apiFetch<GithubManualSyncResponse>(
      "/api/v1/integrations/github/sync-jobs",
      {
        method: "POST",
        body: JSON.stringify({ submissionId }),
      },
    ),
};

// 알림 API (§7)
export const notificationApi = {
  getList: (params: { page?: number; size?: number; unreadOnly?: boolean } = {}) => {
    const q = new URLSearchParams();
    if (params.page != null) q.set("page", String(params.page));
    if (params.size != null) q.set("size", String(params.size));
    if (params.unreadOnly != null) q.set("unreadOnly", String(params.unreadOnly));
    const qs = q.toString();
    return apiFetch<NotificationListResponse>(
      `/api/v1/notifications${qs ? `?${qs}` : ""}`,
    );
  },
  getUnreadCount: () =>
    apiFetch<UnreadCountResponse>("/api/v1/notifications/unread-count"),
  markRead: (notificationId: string) =>
    apiFetch<MarkReadResponse>(
      `/api/v1/notifications/${notificationId}/read`,
      { method: "PATCH" },
    ),
  markAllRead: () =>
    apiFetch<MarkAllReadResponse>("/api/v1/notifications/read-all", {
      method: "PATCH",
    }),
  getPreferences: () =>
    apiFetch<NotificationPreferences>("/api/v1/notifications/preferences"),
  updatePreferences: (body: UpdateNotificationPreferencesRequest) =>
    apiFetch<NotificationPreferences>("/api/v1/notifications/preferences", {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
};
