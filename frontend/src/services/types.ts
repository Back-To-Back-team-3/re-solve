// --- api-spec §1 인증·회원 타입 ---

export type UserRole = "USER" | "ADMIN";
export type UserStatus = "ACTIVE" | "SUSPENDED" | "WITHDRAWN";

// GET /api/v1/members/me §1.2.1
export type Member = {
  memberId: string;
  githubLogin: string;
  nickname: string;
  profileImageUrl: string | null;
  email: string | null;
  role: UserRole;
  status: UserStatus;
  hasLearningProfile: boolean;
  createdAt: string;
};

// PATCH /api/v1/members/me §1.2.2 응답
export type MemberUpdateResponse = {
  memberId: string;
  nickname: string;
};

// POST /api/v1/auth/token §1.1.3 응답
export type AuthTokenResponse = {
  accessToken: string;
  expiresIn: number;
  member: {
    memberId: string;
    nickname: string;
    role: UserRole;
    status: UserStatus;
    isNewMember: boolean;
  };
};

// GET /api/v1/members/me/tag-levels §1.4.1
export type TagLevelBand = "GOOD" | "NORMAL" | "WEAK";

export type TagLevelAchievement = {
  difficulty: number;
  solvedCount: number;
  achievementRate: number;
  band: TagLevelBand;
};

export type TagLevelDiagnosis = {
  isEligible: boolean;
  cooldownEndsAt: string | null;
  inProgressDiagnosisId: string | null;
};

export type TagLevel = {
  tagId: string;
  level: string; // "LV1", "LV2", …
  accessibleMaxDifficulty: number;
  achievements: TagLevelAchievement[];
  diagnosis: TagLevelDiagnosis;
  nextPromotion: {
    targetLevel: string;
    requiredSolvedCount: number;
    requiredAchievementRate: number;
  } | null;
};

// GET /api/v1/admin/members §1.6.1 행 단위
export type AdminMember = {
  memberId: string;
  nickname: string;
  githubLogin: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
};

// --- 기존 mock 타입 (다른 도메인 화면에서 사용 중) ---
// 주의: 아래 User는 mock-api 전용이다. 실서버 회원 타입은 위의 Member를 사용한다.

export type Language = "java" | "python3" | "cpp";
export type Verdict = "AC" | "WA" | "CE" | "TLE" | "MLE" | "RE";
// api-spec §0.1 enumeration table
export type JudgeState =
  | "QUEUED"
  | "JUDGING"
  | "RETRY_WAITING"
  | "COMPLETED"
  | "FAILED";
export type SolvedStatus = "SOLVED" | "UNSOLVED";
export interface Tag {
  id: string;
  name: string;
  pet: string;
  level: number;
  ac: number;
  rate: number;
}
export interface Problem {
  id: string;
  problemRevisionId: string;
  title: string;
  difficulty: number;
  tags: { tagId: string; name: string }[];
  description: string;
  constraints: string;
  examples: string;
  functionSpec: {
    name: "solution";
    parameters: { name: string; type: string }[];
    returnType: string;
  };
  starterCodes: Record<Language, string>;
  samples: { input: string; expected: string }[];
}
export interface Submission {
  id: string;
  userId: string;
  problemId: string;
  problemRevisionId: string;
  examId?: string;
  language: Language;
  sourceCode: string;
  kind: "run" | "submit";
  state: JudgeState;
  verdict: Verdict | null;
  createdAt: number;
  finishedAt?: number;
  passedCount: number;
  totalCount: number;
  cpuTimeMs: number;
  memoryKiB: number;
}
export interface User {
  id: string;
  name: string;
  email: string;
  goal: string;
}
export interface Message {
  id: string;
  scope: string;
  userId: string;
  author: string;
  body: string;
  createdAt: number;
  parentId?: string;
}
export interface Study {
  id: string;
  title: string;
  description: string;
  goal: string;
  level: string;
  time: string;
  capacity: number;
  visibility: "public" | "private";
  condition: string;
  tagIds: string[];
  problemIds: string[];
  members: string[];
  applicants: string[];
  ownerId: string;
  deadline: number;
  reason: string;
}
export type ExamStatus = "recruiting" | "scheduled" | "running" | "ended";
export interface Exam {
  id: string;
  kind: "exam" | "contest";
  title: string;
  description: string;
  duration: number;
  status: ExamStatus;
  startsAt: number;
  problemIds: string[];
  participants: string[];
  visibility: "public" | "private";
  condition: string;
  ownerId: string;
}
export interface ExamSession {
  id: string;
  examId: string;
  userId: string;
  startedAt: number;
  endsAt: number;
  finishedAt?: number;
}
export interface Database {
  version: 1;
  seededAt: number;
  currentUserId: string | null;
  users: User[];
  studies: Study[];
  exams: Exam[];
  sessions: ExamSession[];
  submissions: Submission[];
  messages: Message[];
  notes: Record<string, string>;
  scenario: Verdict | "FAILED";
  simulateReadError: boolean;
}
// --- api-spec §2.1 problem-service B 도메인 실서버 타입 ---

// GET /api/v1/problems §2.1.1 목록 항목
export interface ProblemListItem {
  problemId: string;
  problemRevisionId: string;
  revisionNumber: number;
  title: string;
  difficulty: number;
  tags: { tagId: string; name: string }[];
  publishedAt: string;
  solvedStatus: SolvedStatus;
  supportedLanguages: Language[];
}

// 공통 서버 페이지 래퍼 (api-spec §0.1)
export interface ServerPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

// ===== Contest/Exam 실서버 타입 (api-spec §4) =====

export type ExamStatusServer =
  | "SCHEDULED"
  | "IN_PROGRESS"
  | "CLOSED"
  | "FINALIZED"
  | "CANCELED";

export type ParticipantStatus =
  | "REGISTERED"
  | "STARTED"
  | "FINISHED"
  | "ABSENT"
  | "CANCELED";

export type ResultStatus = "HIDDEN" | "PROVISIONAL" | "FINALIZED";

export type SubmissionReceptionStatus =
  | "ACCEPTED"
  | "REQUESTED"
  | "JUDGED"
  | "FAILED";

export interface MyParticipation {
  participantId: string;
  status: ParticipantStatus;
  enteredAt: string | null;
  personalEndsAt: string;
}

export interface ExamSummary {
  examId: string;
  studyId: string;
  title: string;
  status: ExamStatusServer;
  startsAt: string;
  endsAt: string;
  participantCount: number;
  problemCount: number;
}

export interface ExamDetail {
  examId: string;
  studyId: string;
  title: string;
  mode: string;
  status: ExamStatusServer;
  startsAt: string;
  endsAt: string;
  serverNow: string;
  totalScore: string;
  problemCount: number;
  examRevision: string;
  version: string;
  resultRevision: string;
  myParticipation: MyParticipation | null;
}

export interface ExamProblem {
  problemId: string;
  title: string;
  body: string;
  score: string | null;
  languages: Language[];
}

export interface ExamProblemsResponse {
  problems: ExamProblem[];
  serverNow: string;
  personalEndsAt: string;
}

export interface DraftSaveRequest {
  language: Language;
  sourceCode: string;
  seq: string;
}

export interface DraftSaveResponse {
  applied: boolean;
  serverSeq: string;
  savedAt: string;
  personalEndsAt: string;
  serverNow: string;
}

export interface ExamSubmitRequest {
  problemId: string;
  language: Language;
  sourceCode: string;
  seq: string;
}

export interface ExamSubmitResponse {
  examSubmissionId: string;
  status: SubmissionReceptionStatus;
  receivedAt: string;
  draftSeq: string;
  personalEndsAt: string;
}

export interface ExamFinishResponse {
  participantId: string;
  status: ParticipantStatus;
  personalEndsAt: string;
  autoSubmittedAt: string | null;
  autoSubmissionIds: string[];
}

export interface RankingItem {
  memberId: string;
  nickname: string;
  score: string | null;
  rank: number | null;
  pending: boolean;
}

export interface RankingResponse {
  resultStatus: ResultStatus;
  resultRevision: number;
  items: RankingItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface MyResultProblem {
  problemId: string;
  verdict: string | null;
  score: string | null;
  passedCount: number | null;
  totalCount: number | null;
}

export interface MyResultResponse {
  resultStatus: ResultStatus;
  rank: number | null;
  score: string | null;
  problems: MyResultProblem[];
}

export interface CreateExamProblem {
  problemId: string;
  score: string;
  displayOrder: number;
}

export interface CreateExamRequest {
  title: string;
  mode: "FIXED";
  startsAt: string;
  endsAt: string;
  problems: CreateExamProblem[];
}

export interface ExamCreated {
  examId: string;
  studyId: string;
  status: ExamStatusServer;
  examRevision: string;
  version: string;
  resultRevision: string;
  startsAt: string;
  endsAt: string;
  totalScore: string;
}

export interface ExamStartResponse {
  serverNow: string;
}

export interface ProblemFilter {
  keyword?: string;
  tagIds?: string;
  difficulty?: string;
  solvedStatus?: string;
  sort?: string;
  page?: number;
  size?: number;
}

// GET /api/v1/problems/{problemId} §2.1.2 상세 조회
export type ProblemFunctionParamType = {
  kind: string;
  elementType?: ProblemFunctionParamType;
};

export interface ProblemDetail {
  problemId: string;
  problemRevisionId: string;
  revisionNumber: number;
  title: string;
  difficulty: number;
  tags: { tagId: string; name: string }[];
  publishedAt: string;
  solvedStatus: SolvedStatus;
  description: string;
  constraints: string;
  examples: string;
  functionSpec: {
    name: "solution";
    parameters: { name: string; type: ProblemFunctionParamType }[];
    returnType: ProblemFunctionParamType;
  };
  executionLimits: {
    timeMs: number;
    memoryKb: number;
    wallTimeMs: number;
    outputLimitKb: number;
  };
  supportedLanguages: Language[];
  starterCodes: { language: Language; sourceCode: string }[];
  samples: {
    testAssetId: string;
    argumentTexts: Record<string, string>;
    expectedReturnText: string;
  }[];
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// --- api-spec §3.1 judge-service B 도메인 실서버 타입 ---

// POST /api/v1/runs §3.1.8 테스트 항목
export type RunTestResult = {
  source: "SAMPLE" | "CUSTOM";
  testAssetId?: string;
  index?: number;
  verdict: Verdict | null;
  actualReturnText: string | null;
  expectedReturnText?: string;
  timeMs: number | null;
  memoryKb: number | null;
  stdout: string;
};

// POST /api/v1/runs §3.1.8 응답
export type RunResponse = {
  results: RunTestResult[];
  compileMessage: string | null;
};

// GET /api/v1/submissions/{submissionId} §3.1.3 응답
export type JudgeResult = {
  submissionId: string;
  status: JudgeState;
  effectiveResult: {
    verdict: Verdict | null;
    passedCount: number;
    totalCount: number;
    maxTimeMs: number | null;
    maxMemoryKb: number | null;
  } | null;
  compileMessage: string | null;
};

// GET /api/v1/me/submissions §3.1.4 목록 항목
export type SubmissionHistoryItem = {
  submissionId: string;
  problemId: string;
  language: Language;
  context: "PRACTICE" | "EXAM" | "DIAGNOSIS";
  contextId: string | null;
  status: JudgeState;
  verdict: Verdict | null;
  maxTimeMs: number | null;
  maxMemoryKb: number | null;
  submittedAt: string;
};

// --- api-spec §2.3 관리자 문제 타입 ---

export type ProblemVisibility = "PUBLIC" | "PRIVATE" | "ARCHIVED";

// GET /api/v1/admin/problems §2.3.2 목록 항목
export type AdminProblemListItem = {
  problemId: string;
  problemRevisionId: string;
  revisionNumber: number;
  title: string;
  authorId: string;
  scope: "GENERAL" | "EXAM_ONLY";
  visibility: ProblemVisibility;
  rowVersion: number;
  currentProblemRevisionId: string | null;
  supportedLanguages: Language[];
};

// POST /api/v1/submissions §3.1.2 접수 응답 (202)
export type SubmissionReceipt = {
  submissionId: string;
  judgeAttempt: number;
  status: JudgeState;
  submittedAt: string;
  statusUrl: string;
};

// --- api-spec §5 study-service D 도메인 실서버 타입 ---

export type GoalType = "EMPLOYMENT" | "CONTEST" | "STUDY";
export type SkillLevel = "LV0" | "LV1" | "LV2" | "LV3" | "MASTER";
export type ActiveTimeSlot = "MORNING" | "AFTERNOON" | "EVENING" | "NIGHT";
export type JoinType = "INSTANT" | "APPROVAL";
export type StudyStatus = "RECRUITING" | "ACTIVE" | "CLOSED";
export type MembershipStatus =
  | "PENDING"
  | "APPROVED"
  | "LEFT"
  | "REMOVED"
  | "REJECTED"
  | "CANCELED"
  | "EXPIRED";
export type MembershipRole = "LEADER" | "MANAGER" | "MEMBER";
export type AssignmentStatus = "SCHEDULED" | "OPEN" | "CLOSED";
export type AssignmentItemStatus =
  | "NOT_STARTED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "INCOMPLETE";
export type CompletionType = "PRE_SOLVED" | "ON_TIME" | "LATE";
export type AssignmentProblemStatus = "ACTIVE" | "EXCLUDED";

// GET /api/v1/studies 항목 (api-spec §5.1.1)
export type StudySummary = {
  studyId: string;
  name: string;
  goal: GoalType;
  skillLevel: SkillLevel;
  preferredLanguage: Language;
  activeTimeSlot: ActiveTimeSlot;
  capacity: number;
  memberCount: number;
  status: StudyStatus;
  joinType: JoinType;
  lastActivityAt: string;
  createdAt: string;
};

// GET /api/v1/studies/me 항목 (api-spec §5.1.3)
export type StudyMeItem = {
  studyId: string;
  name: string;
  studyStatus: StudyStatus;
  membershipId: string;
  membershipStatus: MembershipStatus;
  role: MembershipRole;
  joinedAt: string;
};

// GET /api/v1/studies/recommendations content 항목 (api-spec §5.1.4)
export type StudyRecommendation = {
  studyId: string;
  name: string;
  score: number | null;
  reasons: string[];
  memberCount: number;
  capacity: number;
  status: StudyStatus;
};

// GET /api/v1/studies/recommendations data 래퍼 (api-spec §5.1.4)
export type StudyRecommendationsPage = ServerPage<StudyRecommendation> & {
  mode: "PERSONALIZED" | "POPULAR";
  isProfileRequired: boolean;
};

// GET /api/v1/studies/{studyId} (api-spec §5.1.2)
export type StudyDetail = {
  studyId: string;
  name: string;
  description: string;
  goal: GoalType;
  skillLevel: SkillLevel;
  preferredLanguage: Language;
  activeTimeSlot: ActiveTimeSlot;
  capacity: number;
  memberCount: number;
  status: StudyStatus;
  joinType: JoinType;
  isAutoReopen: boolean;
  version: number;
  leader: { memberId: string; nickname: string };
  myMembership: {
    membershipId: string;
    status: MembershipStatus;
    role: MembershipRole;
  } | null;
  createdAt: string;
};

// GET /api/v1/studies/{studyId}/memberships 항목 (api-spec §5.2.4)
export type StudyMemberItem = {
  membershipId: string;
  memberId: string;
  nickname: string;
  role: MembershipRole;
  status: MembershipStatus;
  joinedAt: string | null;
  appliedAt?: string;
  expiresAt?: string | null;
  learningProfile?: {
    goal: GoalType;
    tagLevels: Record<string, string>;
    preferredLanguages: Language[];
  } | null;
};

// POST /api/v1/studies/{studyId}/memberships 응답 (api-spec §5.2.2)
export type MembershipCreated = {
  membershipId: string;
  status: MembershipStatus;
  expiresAt: string | null;
};

// GET /api/v1/studies/{studyId}/assignments 항목 (api-spec §5.4.2)
export type AssignmentSummary = {
  assignmentId: string;
  title: string;
  tagId: string;
  status: AssignmentStatus;
  activeProblemCount: number;
  startsAt: string;
  deadlineAt: string | null;
  myStatus: {
    status: AssignmentItemStatus;
    completedCount: number;
    onTimeCount: number;
  } | null;
};

// GET /api/v1/studies/{studyId}/assignments/{assignmentId} (api-spec §5.4.3)
export type AssignmentDetail = {
  assignmentId: string;
  title: string;
  goal: string;
  tagId: string;
  status: AssignmentStatus;
  isPreSolvedAllowed: boolean;
  startsAt: string;
  deadlineAt: string | null;
  problems: Array<{
    problemId: string;
    assignedRevisionId: string;
    revisionNumber: number | null;
    title: string;
    difficulty: number;
    tags: string[];
    status: AssignmentProblemStatus;
    myCompletionType: CompletionType | null;
  }>;
  memberProgress: Array<{
    memberId: string;
    nickname: string;
    status: AssignmentItemStatus;
    completedCount: number;
    onTimeCount: number;
    activeProblemCount: number;
  }>;
  studyStats: {
    avgCompletionRate: number;
    avgOnTimeRate: number;
  };
};

// --- api-spec §7 notification-service A 도메인 실서버 타입 ---

export type NotificationType =
  | "SUBMISSION_JUDGED"
  | "SUBMISSION_FAILED"
  | "EXAM_STARTING_SOON"
  | "EXAM_FINALIZED"
  | "EXAM_UPDATED"
  | "EXAM_CANCELED"
  | "CONTEST_STARTED"
  | "CONTEST_FINALIZED"
  | "CONTEST_CANCELED"
  | "STUDY_APPLICATION_DECIDED"
  | "ASSIGNMENT_PUBLISHED"
  | "ASSIGNMENT_DEADLINE_APPROACHING"
  | "ASSIGNMENT_INCOMPLETE_REPEATED"
  | "LEVEL_CHANGED"
  | "HINT_READY"
  | "GITHUB_SYNC_FAILED"
  | "SYSTEM_NOTICE";

// GET /api/v1/notifications 항목 (api-spec §7.1.1)
export interface NotificationItem {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  linkUrl: string;
  isRead: boolean;
  createdAt: string;
}

// GET /api/v1/notifications 응답 (api-spec §7.1.1)
export interface NotificationListResponse {
  unreadCount: number;
  content: NotificationItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

// GET /api/v1/notifications/unread-count 응답 (api-spec §7.1.2)
export interface UnreadCountResponse {
  unreadCount: number;
}

// PATCH /api/v1/notifications/{notificationId}/read 응답 (api-spec §7.1.3)
export interface MarkReadResponse {
  unreadCount: number;
}

// PATCH /api/v1/notifications/read-all 응답 (api-spec §7.1.4)
export interface MarkAllReadResponse {
  updatedCount: number;
  unreadCount: number;
}

// SSE /api/v1/notifications/subscribe 이벤트 페이로드 (api-spec §7.1.1)
export interface SSENotificationEvent {
  notificationId: string;
  type: NotificationType;
  title: string;
  content: string;
  linkUrl: string;
  createdAt: string;
}
