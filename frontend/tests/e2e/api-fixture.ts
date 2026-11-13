import type { Page, Route } from "@playwright/test";
import type {
  ExamDetail,
  ExamProblemsResponse,
  JudgeResult,
  Member,
  MembershipRole,
  MembershipStatus,
  MyParticipation,
  ProblemDetail,
  ServerPage,
  StudyDetail,
  StudyMeItem,
  StudySummary,
  SubmissionAttemptsResponse,
  SubmissionHistoryItem,
  SubmissionSource,
  SubmissionTestsResponse,
} from "../../src/services/types";
import { fulfillCatalog } from "./catalog-fixture";
import { problemDetail } from "./practice-fixture";

/** 모든 시각의 기준. 브라우저 시계도 `installClock`으로 이 시각에 맞춰 날짜에 따라 결과가 달라지지 않게 한다. */
export const BASE_TIME = "2026-11-14T10:00:00+09:00";
const BASE_MS = Date.parse(BASE_TIME);
const at = (seconds: number) => new Date(BASE_MS + seconds * 1000).toISOString();

export const member: Member = {
  memberId: "7", githubLogin: "tester", nickname: "테스터", profileImageUrl: null, email: null,
  role: "USER", status: "ACTIVE", hasLearningProfile: true, createdAt: at(-86400),
};

export const problems: ProblemDetail[] = [
  problemDetail as ProblemDetail,
  {
    ...(problemDetail as ProblemDetail),
    problemId: "1002", problemRevisionId: "2002", title: "고정 회차 배열 최댓값", difficulty: 2,
    tags: [{ tagId: "2", name: "정렬" }], description: "배열의 최댓값을 반환하세요.",
  },
];

const pageOf = <T>(content: T[]): ServerPage<T> => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false,
});

export { fulfillCatalog };

const ok = (route: Route, data: unknown) => route.fulfill({ json: { success: true, message: null, data } });
const fail = (route: Route, status: number, code: string, message: string) =>
  route.fulfill({ status, json: { success: false, error: { code, message } } });

export const INSTANT_STUDY_ID = "11";
export const APPROVAL_STUDY_ID = "12";
export const EXAM_ID = "31";

const studySummary = (studyId: string, name: string, joinType: "INSTANT" | "APPROVAL"): StudySummary => ({
  studyId, name, goal: "EMPLOYMENT", skillLevel: "LV1", preferredLanguage: "java", activeTimeSlot: "EVENING",
  capacity: 10, memberCount: 3, status: "RECRUITING", joinType, lastActivityAt: at(-3600), createdAt: at(-86400),
});
const studies = [
  studySummary(INSTANT_STUDY_ID, "즉시 가입 스터디", "INSTANT"),
  studySummary(APPROVAL_STUDY_ID, "승인 가입 스터디", "APPROVAL"),
];

/** POST /api/v1/studies 요청 본문 (api-spec §5.1). 화면이 보내는 필드를 그대로 받아 상세에 반영한다. */
type StudyCreateBody = Pick<StudySummary, "name" | "goal" | "skillLevel" | "preferredLanguage" | "activeTimeSlot"
  | "capacity" | "joinType"> & { description: string; isAutoReopen: boolean };

export type ApiFixtureOptions = {
  /** false면 토큰 갱신이 401을 돌려 비로그인 상태로 시작한다. */
  loggedIn?: boolean;
  /** 연습 제출의 최종 판정. FAILED는 채점 시스템 실패로 종결한다. */
  verdict?: "AC" | "WA" | "FAILED";
  /** 시험 종료까지 남은 초. 만료 시나리오에서만 짧게 둔다. */
  examEndsInSeconds?: number;
  /** 기본 응답보다 먼저 호출된다. 처리했으면 true를 돌려준다(409·401 등 테스트별 응답). */
  handle?: (route: Route, path: string, method: string) => Promise<boolean> | boolean;
};

/**
 * 실서버 API 계약(api-spec) 형식으로 화면 전체 흐름에 응답한다.
 * 처리하지 않는 경로는 404로 응답해, 화면이 계약에 없는 경로를 부르면 테스트가 바로 실패하게 한다.
 */
export async function mockApi(page: Page, options: ApiFixtureOptions = {}) {
  const { loggedIn = true, verdict = "AC", examEndsInSeconds = 3600, handle } = options;
  const startedAt = Date.now();
  const serverNow = () => new Date(BASE_MS + (Date.now() - startedAt)).toISOString();
  const memberships: Record<string, { membershipId: string; status: MembershipStatus; role: MembershipRole } | null> = {
    [INSTANT_STUDY_ID]: null, [APPROVAL_STUDY_ID]: null,
  };
  // 생성한 스터디는 이 화면 흐름 안에서만 목록·상세에 나타나야 하므로 모듈 상수가 아닌 호출별 목록에 둔다.
  const studyList: StudySummary[] = [...studies];
  const descriptions: Record<string, string> = {};
  let submissionPolls = 0;
  let participation: MyParticipation | null = null;
  const examEndsAt = at(examEndsInSeconds);

  const studyDetail = (studyId: string): StudyDetail => {
    const s = studyList.find((x) => x.studyId === studyId)!;
    const mine = memberships[studyId];
    return {
      ...s, description: descriptions[studyId] ?? `${s.name} 소개`, isAutoReopen: false, version: 1,
      leader: mine?.role === "LEADER" ? { memberId: member.memberId, nickname: member.nickname }
        : { memberId: "1", nickname: "스터디장" },
      myMembership: mine,
    };
  };
  const examDetail = (): ExamDetail => ({
    examId: EXAM_ID, studyId: INSTANT_STUDY_ID, title: "주간 모의 코테", mode: "FIXED", status: "IN_PROGRESS",
    startsAt: at(-600), endsAt: examEndsAt, serverNow: serverNow(), totalScore: "200", problemCount: 2,
    examRevision: "1", version: "1", resultRevision: "0", myParticipation: participation,
  });
  const judgeResult = (submissionId: string): JudgeResult => {
    submissionPolls += 1;
    const done = submissionPolls >= 3;
    const failed = done && verdict === "FAILED";
    return {
      submissionId, problemId: "1001", problemRevisionId: "2001", language: "java", context: "PRACTICE",
      judgeAttempt: 1, submittedAt: serverNow(), failureCode: failed ? "ENGINE_UNAVAILABLE" : null,
      status: !done ? (submissionPolls === 1 ? "QUEUED" : "JUDGING") : failed ? "FAILED" : "COMPLETED",
      effectiveResult: done && !failed ? {
        verdict: verdict === "WA" ? "WA" : "AC", passedCount: verdict === "WA" ? 3 : 5, totalCount: 5,
        maxTimeMs: 12, maxMemoryKb: 20480,
      } : null,
      compileMessage: null,
    };
  };
  const history: SubmissionHistoryItem[] = [{
    submissionId: "5000", problemId: "1001", language: "java", context: "PRACTICE", contextId: null,
    status: "COMPLETED", verdict: "AC", maxTimeMs: 10, maxMemoryKb: 20480, submittedAt: at(-7200),
  }];

  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    if (handle && (await handle(route, path, method))) return;

    // SSE는 page.route로 스트림을 흉내 내기 어려워 연결만 성립시키고 실시간 전달은 수동 테스트로 확인한다.
    if (path === "/api/v1/notifications/subscribe" || /^\/api\/v1\/exams\/[^/]+\/events$/.test(path))
      return route.fulfill({ status: 200, headers: { "Content-Type": "text/event-stream" }, body: "" });

    if (path === "/api/v1/auth/token/refresh")
      return loggedIn ? ok(route, { accessToken: "test-access", expiresIn: 3600 })
        : fail(route, 401, "UNAUTHORIZED", "로그인이 필요합니다");
    if (path === "/api/v1/members/me")
      return method === "PATCH" ? ok(route, { memberId: member.memberId, nickname: route.request().postDataJSON().nickname })
        : loggedIn ? ok(route, member) : fail(route, 401, "UNAUTHORIZED", "로그인이 필요합니다");
    if (path === "/api/v1/auth/logout") return route.fulfill({ status: 204 });
    if (path === "/api/v1/members/me/tag-levels") return ok(route, { content: [] });
    if (path === "/api/v1/notifications/unread-count") return ok(route, { unreadCount: 0 });
    if (path === "/api/v1/notifications") return ok(route, { unreadCount: 0, ...pageOf([]) });
    if (path === "/api/v1/integrations/github") return ok(route, { status: "NOT_CONNECTED" });

    if (await fulfillCatalog(route, path)) return;
    const problem = problems.find((p) => path === `/api/v1/problems/${p.problemId}`);
    if (problem) return ok(route, problem);
    if (path === "/api/v1/judge/languages") return ok(route, { content: [
      { language: "java", displayName: "Java 17", runtimeVersion: "17", enabled: true },
      { language: "python3", displayName: "Python 3", runtimeVersion: "3", enabled: true },
    ] });
    if (path === "/api/v1/runs") return ok(route, { compileMessage: null, results: [{
      source: "SAMPLE", testAssetId: "1", verdict: "AC", actualReturnText: "3", expectedReturnText: "3",
      timeMs: 5, memoryKb: 10240, stdout: "",
    }] });
    if (path === "/api/v1/submissions" && method === "POST") {
      submissionPolls = 0;
      return ok(route, { submissionId: "5001", judgeAttempt: 1, status: "QUEUED", submittedAt: serverNow(),
        statusUrl: "/api/v1/submissions/5001" });
    }
    if (path === "/api/v1/submissions/5001") return ok(route, judgeResult("5001"));
    // 제출 이력의 상세 화면(/submissions/5000)이 부르는 조회 경로. 채점이 끝난 정답 기록으로 고정한다.
    const past = /^\/api\/v1\/submissions\/5000(\/.*)?$/.exec(path);
    if (past) {
      const rest = past[1] ?? "";
      if (rest === "") return ok(route, {
        submissionId: "5000", problemId: "1001", problemRevisionId: "2001", language: "java", context: "PRACTICE",
        judgeAttempt: 1, submittedAt: at(-7200), failureCode: null, status: "COMPLETED", compileMessage: null,
        effectiveResult: { verdict: "AC", passedCount: 5, totalCount: 5, maxTimeMs: 10, maxMemoryKb: 20480 },
      } satisfies JudgeResult);
      if (rest === "/source") return ok(route, { language: "java", sourceCode: "// past solution" } satisfies SubmissionSource);
      if (rest === "/judge-attempts") return ok(route, { content: [{
        judgeAttempt: 1, trigger: "INITIAL", status: "SUCCEEDED", verdict: "AC", passedCount: 5, totalCount: 5,
        maxTimeMs: 10, maxMemoryKb: 20480, createdAt: at(-7200), finishedAt: at(-7190),
      }] } satisfies SubmissionAttemptsResponse);
      if (rest === "/judge-attempts/1/test-results") return ok(route, { content: [
        { testOrder: 1, isSample: true, verdict: "AC", timeMs: 5, memoryKb: 10240, argumentTexts: { items: "[1,2]" },
          expectedReturnText: "3", actualReturnText: "3" },
        { testOrder: 2, passed: true },
      ] } satisfies SubmissionTestsResponse);
    }
    if (path === "/api/v1/me/submissions") return ok(route, pageOf(history));

    if (path === "/api/v1/studies" && method === "POST") {
      const body = route.request().postDataJSON() as StudyCreateBody;
      const studyId = String(13 + studyList.length - studies.length);
      studyList.push({ ...studySummary(studyId, body.name, body.joinType), goal: body.goal,
        skillLevel: body.skillLevel, preferredLanguage: body.preferredLanguage, activeTimeSlot: body.activeTimeSlot,
        capacity: body.capacity, memberCount: 1, createdAt: serverNow(), lastActivityAt: serverNow() });
      descriptions[studyId] = body.description;
      memberships[studyId] = { membershipId: `m-${studyId}`, status: "APPROVED", role: "LEADER" };
      return route.fulfill({ status: 201, json: { success: true, message: "스터디가 생성되었습니다.", data: { studyId } } });
    }
    if (path === "/api/v1/studies") return ok(route, pageOf(studyList));
    if (path === "/api/v1/studies/recommendations")
      return ok(route, { ...pageOf([]), mode: "POPULAR", isProfileRequired: false });
    if (path === "/api/v1/studies/me") return ok(route, pageOf<StudyMeItem>(
      studyList.flatMap((s) => {
        const mine = memberships[s.studyId];
        return mine ? [{ studyId: s.studyId, name: s.name, studyStatus: s.status, membershipId: mine.membershipId,
          membershipStatus: mine.status, role: mine.role, joinedAt: at(0) }] : [];
      }),
    ));
    const study = /^\/api\/v1\/studies\/(\d+)(\/.*)?$/.exec(path);
    if (study && study[1] in memberships) {
      const [, studyId, rest = ""] = study;
      if (rest === "") return ok(route, studyDetail(studyId));
      if (rest === "/memberships" && method === "POST") {
        const status: MembershipStatus = studyId === INSTANT_STUDY_ID ? "APPROVED" : "PENDING";
        memberships[studyId] = { membershipId: `m-${studyId}`, status, role: "MEMBER" };
        return ok(route, { membershipId: `m-${studyId}`, status,
          expiresAt: status === "PENDING" ? at(7 * 86400) : null });
      }
      if (rest === "/memberships/me" && method === "DELETE") {
        memberships[studyId] = null;
        return route.fulfill({ status: 204 });
      }
      if (rest === "/memberships") return ok(route, pageOf([{ membershipId: "m-1", memberId: "1", nickname: "스터디장",
        role: "LEADER", status: "APPROVED", joinedAt: at(-86400) }]));
      if (rest === "/assignments") return ok(route, pageOf([]));
      if (rest === "/exams") return ok(route, pageOf(studyId === INSTANT_STUDY_ID ? [{
        examId: EXAM_ID, studyId, title: "주간 모의 코테", status: "IN_PROGRESS", startsAt: at(-600),
        endsAt: examEndsAt, participantCount: 3, problemCount: 2 }] : []));
    }

    if (path === `/api/v1/exams/${EXAM_ID}`) return ok(route, examDetail());
    if (path === `/api/v1/exams/${EXAM_ID}/participants` && method === "POST") {
      participation = { participantId: "41", status: "REGISTERED", enteredAt: null, personalEndsAt: examEndsAt };
      return ok(route, participation);
    }
    if (path === `/api/v1/exams/${EXAM_ID}/start`) {
      participation = { participantId: "41", status: "STARTED", enteredAt: serverNow(), personalEndsAt: examEndsAt };
      return ok(route, { serverNow: serverNow() });
    }
    if (path === `/api/v1/exams/${EXAM_ID}/problems`) return ok(route, {
      serverNow: serverNow(), personalEndsAt: examEndsAt,
      problems: problems.map((p) => ({ problemId: p.problemId, title: p.title, body: p.description, score: "100",
        languages: ["java", "python3"] })),
    } satisfies ExamProblemsResponse);
    if (path.startsWith(`/api/v1/exams/${EXAM_ID}/drafts/`)) {
      const body = route.request().postDataJSON() as { seq: string };
      return ok(route, { applied: true, serverSeq: body.seq, savedAt: serverNow(), personalEndsAt: examEndsAt,
        serverNow: serverNow() });
    }
    if (path === `/api/v1/exams/${EXAM_ID}/submissions`) {
      const body = route.request().postDataJSON() as { seq: string };
      return ok(route, { examSubmissionId: "61", status: "ACCEPTED", receivedAt: serverNow(), draftSeq: body.seq,
        personalEndsAt: examEndsAt });
    }
    if (path === `/api/v1/exams/${EXAM_ID}/finish`) {
      participation = { participantId: "41", status: "FINISHED", enteredAt: at(0), personalEndsAt: examEndsAt };
      return ok(route, { participantId: "41", status: "FINISHED", personalEndsAt: examEndsAt,
        autoSubmittedAt: null, autoSubmissionIds: [] });
    }
    if (path === `/api/v1/exams/${EXAM_ID}/results/me`) return ok(route, { resultStatus: "PROVISIONAL", rank: 1,
      score: "200", problems: problems.map((p) => ({ problemId: p.problemId, verdict: "AC", score: "100",
        passedCount: 5, totalCount: 5 })) });
    if (path === `/api/v1/exams/${EXAM_ID}/results`) return ok(route, { resultStatus: "PROVISIONAL",
      resultRevision: 1, items: [{ memberId: "7", nickname: "테스터", score: "200", rank: 1, pending: false }],
      nextCursor: null, hasMore: false });

    return fail(route, 404, "NOT_FOUND", path);
  });
}

/** 브라우저 시계를 기준 시각으로 맞춘다. `page.goto` 전에 호출한다. */
export async function installClock(page: Page) {
  await page.clock.install({ time: new Date(BASE_MS) });
}
