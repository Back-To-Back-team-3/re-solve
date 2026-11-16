import { describe, expect, it } from "vitest";
import { compareDiagnosisSeq, diagnosisCodeError, diagnosisDraftRequest, diagnosisDraftKey, restoreDiagnosisBuffer } from "@/services/diagnosis";
import { createDiagnosisStore } from "@/stores/diagnosis";
import type { DiagnosisDetail, DiagnosisProblem } from "@/services/types";

const problem: DiagnosisProblem = {
  problemId: "1", problemRevisionId: "10", difficulty: 1,
  draft: { language: "java", sourceCode: "server", seq: "9007199254740993", savedAt: "2026-11-16T10:00:00+09:00" },
  lastSubmission: null,
};
const detail: DiagnosisDetail = { diagnosisId: "120", tagId: "1", status: "IN_PROGRESS",
  endsAt: "2026-11-16T11:00:00+09:00", serverNow: "2026-11-16T10:00:00+09:00", problems: [problem], result: null };

describe("진단 Draft 복구와 순번", () => {
  it("정수 정밀도를 넘는 순번을 정확하게 비교하고 최신 로컬 Draft와 제출 원본을 복구한다", () => {
    const draft = { language: "python3", sourceCode: "local", seq: "9007199254740994" };
    const pending = { language: "java", sourceCode: "pending", seq: "9007199254740992" };
    expect(compareDiagnosisSeq(draft.seq, problem.draft!.seq)).toBe(1);
    expect(restoreDiagnosisBuffer(problem, JSON.stringify({ revisionId: "10", draft, pending })))
      .toMatchObject({ draft, pending, savedSeq: problem.draft!.seq });
  });
  it("동일 순번은 서버를 선택하고 다른 회차·손상·잘못된 언어·순번을 복구하지 않는다", () => {
    for (const raw of ["broken", JSON.stringify({ revisionId: "11", draft: { ...problem.draft, seq: "9007199254740995" } }),
      JSON.stringify({ revisionId: "10", draft: { ...problem.draft, sourceCode: "same seq" } }),
      JSON.stringify({ revisionId: "10", draft: { language: "ruby", sourceCode: "wrong", seq: "99" } }),
      JSON.stringify({ revisionId: "10", draft: { language: "java", sourceCode: "wrong", seq: "-1" } })]) {
      expect(restoreDiagnosisBuffer(problem, raw).draft).toEqual(diagnosisDraftRequest(problem.draft!));
    }
    expect(diagnosisDraftKey("7", "120", "1")).not.toBe(diagnosisDraftKey("8", "120", "1"));
    expect(diagnosisDraftKey("7", "120", "1")).not.toBe(diagnosisDraftKey("7", "121", "1"));
  });
  it("편집 후 도착한 저장 응답과 이전 상태 응답이 코드를 덮어쓰거나 응시를 다시 열지 않는다", () => {
    const store = createDiagnosisStore(detail);
    store.getState().edit("1", "java", "new code");
    const seq = store.getState().buffers["1"].draft!.seq;
    store.getState().acknowledge("1", "9007199254740993");
    store.getState().merge({ ...detail, serverNow: "2026-11-16T10:00:01+09:00" });
    expect(store.getState().buffers["1"].draft).toMatchObject({ seq, sourceCode: "new code" });
    store.getState().close();
    store.getState().merge({ ...detail, serverNow: "2026-11-16T10:00:02+09:00" });
    expect(store.getState().detail.status).toBe("CLOSED");
    store.getState().edit("1", "java", "after close");
    expect(store.getState().buffers["1"].draft?.sourceCode).toBe("new code");
  });
  it("서버의 더 큰 순번으로 복구하고 재요청 원본은 편집 후에도 유지한다", () => {
    const store = createDiagnosisStore(detail);
    const original = store.getState().buffers["1"].draft;
    store.getState().setPending("1", original);
    store.getState().edit("1", "cpp", "new language");
    store.getState().merge({ ...detail, serverNow: "2026-11-16T10:00:01+09:00", problems: [{ ...problem,
      draft: { ...problem.draft!, seq: "9007199254740999", sourceCode: "other tab" } }] });
    expect(store.getState().buffers["1"].draft?.sourceCode).toBe("other tab");
    expect(store.getState().buffers["1"].pending).toEqual(original);
  });
  it("복구 응답의 savedAt은 저장·제출 요청에 포함하지 않는다", () => {
    expect(restoreDiagnosisBuffer(problem, null).draft).not.toHaveProperty("savedAt");
    const store = createDiagnosisStore(detail);
    store.getState().merge({ ...detail, serverNow: "2026-11-16T10:00:01+09:00" });
    expect(store.getState().buffers["1"].draft).toEqual(diagnosisDraftRequest(problem.draft!));
  });
  it("초기 기본 코드는 미저장 순번을 유지하고 같은 코드 편집은 순번을 늘리지 않는다", () => {
    const store = createDiagnosisStore({ ...detail, problems: [{ ...problem, draft: null }] });
    store.getState().ensure("1", "java", "starter");
    const initial = store.getState().buffers["1"];
    expect(compareDiagnosisSeq(initial.draft!.seq, initial.savedSeq)).toBe(1);
    store.getState().edit("1", "java", "starter");
    expect(store.getState().buffers["1"].draft!.seq).toBe(initial.draft!.seq);
    store.getState().acknowledge("1", initial.draft!.seq);
    expect(store.getState().buffers["1"].savedSeq).toBe(initial.draft!.seq);
  });
  it("언어별 로컬 코드를 보관하되 새 서버 Draft의 언어는 서버 코드로 복구한다", () => {
    const local = JSON.stringify({ revisionId: "10", codes: { java: "local java", python3: "local python", ruby: "invalid" } });
    const buffer = restoreDiagnosisBuffer(problem, local);
    expect(buffer.codes).toEqual({ java: "server", python3: "local python" });
    const store = createDiagnosisStore(detail);
    store.getState().edit("1", "python3", "python answer");
    expect(store.getState().buffers["1"].codes).toEqual({ java: "server", python3: "python answer" });
  });
  it("UTF-8 64KiB 경계와 공백 제출을 검증하고 빈 Draft 저장은 허용한다", () => {
    expect(diagnosisCodeError("a".repeat(65536), true)).toBe("");
    expect(diagnosisCodeError("a".repeat(65537), true)).not.toBe("");
    expect(diagnosisCodeError("가".repeat(21846))).not.toBe("");
    expect(diagnosisCodeError("", false)).toBe("");
    expect(diagnosisCodeError("  ", true)).not.toBe("");
  });
});
