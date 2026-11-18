import { contestApi, isUnauthorized, judgeApi } from "@/services/api";
import { expiredPracticeRequestMessage, isPracticeRequestExpired } from "@/services/practice-request";
import type { Language, Problem } from "@/services/types";
import type { useWorkspaceApi } from "@/stores/workspace";

/** 미접수 요청의 원본을 보관하고, 재요청은 같은 코드·키 또는 시험 순번을 사용한다. */
export async function submitEditorCode({ workspace, submissionKey, problem, language, sourceCode, seq, examId, retry, onStart, onAccepted, toLogin }: {
  workspace: ReturnType<typeof useWorkspaceApi>; submissionKey: string; problem: Problem; language: Language;
  sourceCode: string; seq: string; examId?: string; retry: boolean;
  onStart: () => void; onAccepted: () => void; toLogin: () => void;
}) {
  const { setEditorSubmissionView: setView } = workspace.getState();
  const pending = workspace.getState().practiceRequests[submissionKey];
  const pendingExam = workspace.getState().examRequests[submissionKey];
  if (workspace.getState().editorSubmissionViews[submissionKey]?.transportId) return;
  if (retry && !(examId ? pendingExam : pending)) return;
  const request = retry && pending ? pending : {
    idempotencyKey: crypto.randomUUID(),
    createdAt: Date.now(),
    body: {
      problemId: problem.id,
      problemRevisionId: problem.problemRevisionId,
      language,
      sourceCode,
    },
  };
  if (!examId && retry && isPracticeRequestExpired(request.createdAt, Date.now())) {
    setView(submissionKey, { requestError: expiredPracticeRequestMessage }); return;
  }
  const transportId = crypto.randomUUID();
  const isCurrent = () => workspace.getState().editorSubmissionViews[submissionKey]?.transportId === transportId;
  const examRequest = retry && pendingExam ? pendingExam : { problemId: problem.id, language, sourceCode, seq };
  onStart();
  // 전송 식별자는 언어 전환에도 유지하고 로그아웃 시 함께 폐기한다.
  setView(submissionKey, { showSubmission: false, requestError: null, acceptedSourceCode: null, transportId });
  if (examId) workspace.getState().setExamRequest(submissionKey, examRequest);
  else workspace.getState().setPracticeRequest(submissionKey, request);
  try {
    if (examId) {
      await contestApi.submitExam(examId, examRequest);
      if (!isCurrent()) return;
      workspace.getState().setExamRequest(submissionKey, undefined);
      setView(submissionKey, { requestError: null, acceptedSourceCode: examRequest.sourceCode });
    } else {
      const receipt = await judgeApi.submit(request.body, request.idempotencyKey);
      if (!isCurrent() || !workspace.getState().acceptPracticeSubmission(submissionKey, request, receipt.submissionId)) return;
      setView(submissionKey, { showSubmission: true, requestError: null });
    }
    onAccepted();
  } catch (failure) {
    if (!isCurrent()) return;
    if (isUnauthorized(failure)) return toLogin();
    const message = typeof failure === "object" && failure !== null && "message" in failure
      && typeof failure.message === "string" ? failure.message : "제출에 실패했습니다.";
    if (!examId && workspace.getState().practiceRequests[submissionKey]?.idempotencyKey !== request.idempotencyKey) return;
    setView(submissionKey, { requestError: message });
  } finally {
    if (isCurrent()) setView(submissionKey, { transportId: null });
  }
}
