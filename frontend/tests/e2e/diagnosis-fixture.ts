import type { Page } from "@playwright/test";
import type { DiagnosisDetail, DiagnosisDraftRequest, DiagnosisHistory } from "../../src/services/types";
import { fulfillCatalog } from "./catalog-fixture";
import { problemDetail } from "./practice-fixture";

/** 실제 진단 API의 상태 전이를 HTTP 경계에서 재현하고 Judge0 호출 없이 화면을 검증한다. */
export async function mockDiagnosis(page: Page) {
  const initialNow = Date.now();
  const state = {
    detail: { diagnosisId: "120", tagId: "1", status: "IN_PROGRESS",
      serverNow: new Date(initialNow).toISOString(), endsAt: new Date(initialNow + 3600000).toISOString(),
      problems: [1, 2, 3].map((difficulty) => ({ problemId: String(1000 + difficulty), problemRevisionId: String(2000 + difficulty), difficulty,
        draft: null, lastSubmission: null })), result: null } as DiagnosisDetail,
    history: [] as DiagnosisHistory[],
    started: false, posts: [] as DiagnosisDraftRequest[], saves: [] as { id: string; body: DiagnosisDraftRequest }[],
    finishes: 0, failSave: false, failSubmit: false, failFinish: false, failStart: false,
    errorCode: "", forbidden: false, failDetail: false,
  };
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    const ok = (data: unknown, status = 200) => route.fulfill({ status, json: { success: true, data } });
    const fail = (code: string, status = 503) => route.fulfill({ status, json: { success: false, error: { code, message: code } } });
    if (await fulfillCatalog(route, path)) return;
    if (path === "/api/v1/auth/token/refresh") return ok({ accessToken: "test-access", expiresIn: 3600 });
    if (path === "/api/v1/members/me") return ok({ memberId: "7", nickname: "테스터", role: "USER", status: "ACTIVE", hasLearningProfile: true });
    if (path === "/api/v1/notifications/unread-count") return ok({ unreadCount: 0 });
    if (path === "/api/v1/members/me/tag-levels") return ok({ content: state.started ? [{ tagId: "1", level: state.detail.result?.appliedLevel ?? "LV0", achievements: [],
      accessibleMaxDifficulty: 0, nextPromotion: null, diagnosis: { isEligible: true, cooldownEndsAt: null,
        inProgressDiagnosisId: state.detail.status === "IN_PROGRESS" ? "120" : null } }] : [] });
    if (path === "/api/v1/judge/languages") return ok({ content: [{ language: "java", displayName: "Java", runtimeVersion: "17", enabled: true }] });
    if (path === "/api/v1/me/submissions") return ok({ content: [], totalPages: 0 });
    if (path === "/api/v1/diagnoses") {
      if (method === "POST") {
        if (state.errorCode) return fail(state.errorCode, 409);
        state.started = true;
        if (state.failStart) { state.failStart = false; return route.abort(); }
        return ok({ ...state.detail, startedAt: state.detail.serverNow }, 201);
      }
      return ok({ content: state.history, page: 0, size: 20, totalElements: state.history.length, totalPages: 1, hasNext: false });
    }
    if (path === "/api/v1/diagnoses/120") {
      if (state.forbidden) return fail("DIAGNOSIS_NOT_FOUND", 404);
      if (state.failDetail) return fail("COMMON_DEPENDENCY_UNAVAILABLE");
      return ok(state.detail);
    }
    if (path === "/api/v1/diagnoses/120/finish") {
      state.finishes++;
      if (state.failFinish) return route.abort();
      if (state.detail.status !== "IN_PROGRESS") return fail("DIAGNOSIS_CLOSED", 409);
      state.detail = { ...state.detail, status: "CLOSED" };
      return ok({ diagnosisId: "120", status: "CLOSED", closedAt: state.detail.serverNow });
    }
    const problem = /^\/api\/v1\/diagnoses\/120\/problems\/(\d+)(?:\/(draft|submissions))?$/.exec(path);
    if (problem) {
      const id = problem[1];
      if (!problem[2]) return ok({ ...problemDetail, problemId: id, problemRevisionId: String(Number(id) + 1000), title: `진단 문제 ${id}`, supportedLanguages: ["java"] });
      const body: DiagnosisDraftRequest = route.request().postDataJSON();
      if (problem[2] === "draft") {
        state.saves.push({ id, body });
        if (state.failSave) return fail("COMMON_DEPENDENCY_UNAVAILABLE");
        if (state.detail.status !== "IN_PROGRESS") return fail("DIAGNOSIS_CLOSED", 409);
        const item = state.detail.problems.find((item) => item.problemId === id)!;
        const applied = !item.draft || BigInt(body.seq) > BigInt(item.draft.seq);
        if (applied) item.draft = { ...body, savedAt: state.detail.serverNow };
        return ok({ applied, seq: item.draft!.seq, savedAt: state.detail.serverNow });
      }
      state.posts.push(body);
      if (state.failSubmit) return route.abort();
      const item = state.detail.problems.find((item) => item.problemId === id)!;
      item.lastSubmission = { submissionStatus: "REQUESTED", draftSeq: body.seq };
      item.draft = { ...body, savedAt: state.detail.serverNow };
      return ok({ diagnosisSubmissionId: "880", status: "ACCEPTED", receivedAt: state.detail.serverNow }, 202);
    }
    return fail("UNEXPECTED_API:" + path, 404);
  });
  return state;
}
