"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Modal, useLoginRedirect } from "@/components/ui";
import { contestApi, isUnauthorized, judgeApi } from "@/services/api";
import { runInputs, runExpectedOutputs, type RunTestCase } from "@/services/run-inputs";
import { draftKey, readDraft, saveDraft, useWorkspace, useWorkspaceApi } from "@/stores/workspace";
import type { ExamRunResponse, Language, Problem, RunResponse } from "@/services/types";
import { JudgePoll, EditorResultPanel } from "@/features/editor-results";
import { ExamRunResult } from "@/features/exams-run-result";
import { EditorResetDialog } from "@/features/editor-reset-dialog";
import { EditorMonaco } from "@/features/editor-monaco";
import { useRunInputDraft } from "@/features/editor-run-draft";
import { EditorRunInputs } from "@/features/editor-run-inputs";
import { EditorActions } from "@/features/editor-actions";
import { EditorSubmissionStatus } from "@/features/editor-submission-status";
import { submitEditorCode } from "@/features/editor-submission";
import { readExamDraft, writeExamDraft } from "@/services/exam-editor-draft";
export function Buffer({
  problem,
  userId,
  examId,
  language,
  fontSize,
  runDisabled = false,
  disabled = false,
}: {
  problem: Problem;
  userId: string;
  examId?: string;
  language: Language;
  fontSize: number;
  runDisabled?: boolean;
  disabled?: boolean;
}) {
  const key = draftKey(userId, problem.id, language, examId),
    setDraft = useWorkspace((s) => s.setDraft),
    memory = useWorkspace((s) => s.drafts[key]);
  const codeRef = useRef(""),
    readyRef = useRef(false),
    seqRef = useRef("1"),
    examDirtyRef = useRef(false),
    timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [code, setCode] = useState(""),
    [ready, setReady] = useState(false),
    [saved, setSaved] = useState("복원 중…"),
    [reset, setReset] = useState(false),
    [testCasesOpen, setTestCasesOpen] = useState(false),
    [paused, setPaused] = useState(false),
    [pollVersion, setPollVersion] = useState(0),
    [runResult, setRunResult] = useState<RunResponse | null>(null),
    [examRunResult, setExamRunResult] = useState<ExamRunResponse | null>(null),
    [runExpectations, setRunExpectations] = useState<(string | null)[]>([]),
    [runError, setRunError] = useState<string | null>(null),
    [busyRun, setBusyRun] = useState(false),
    [showRun, setShowRun] = useState(false),
    [judgeError, setJudgeError] = useState<string | null>(null);
  const runInputKey = JSON.stringify([userId, examId ?? "practice", problem.id, problem.problemRevisionId]);
  const { configuration: runConfiguration, storageError: runStorageError } = useRunInputDraft(runInputKey);
  const setRunInputs = useWorkspace((state) => state.setPracticeRunInputs);
  const customInputs = runConfiguration?.customInputs ?? [];
  const setCustomInputs = (value: RunTestCase[]) => setRunInputs(runInputKey, { useSamples: true, customInputs: value });
  const submissionKey = JSON.stringify([key, problem.problemRevisionId]);
  const submissions = useWorkspace((state) => state.practiceSubmissions);
  const pendingExam = useWorkspace((state) => state.examRequests[submissionKey]);
  const pending = useWorkspace((state) => state.practiceRequests[submissionKey]);
  const view = useWorkspace((state) => state.editorSubmissionViews[submissionKey]);
  const setView = useWorkspace((state) => state.setEditorSubmissionView);
  const showSubmission = view?.showSubmission ?? !(pending || pendingExam);
  const requestError = view?.requestError ?? null;
  const sending = !!view?.transportId;
  const activeId = useWorkspace((state) => state.activePracticeSubmissions[submissionKey]);
  const attempt = activeId ? submissions[activeId] : undefined;
  const accepted = Object.values(submissions).flatMap((item) => item?.contextKey === submissionKey ? [item] : []);
  const workspace = useWorkspaceApi();
  const router = useRouter(), toLogin = useLoginRedirect();
  const judgeSubmissionId = attempt?.submissionId ?? null;
  const judgeResult = attempt?.result ?? null;
  const polling = showSubmission && !!judgeSubmissionId && !judgeResult && !paused;
  const busy = sending || polling;
  useEffect(() => {
    let initial: typeof memory | undefined = memory;
    try {
      if (examId) {
        const draft = readExamDraft(examId, problem.id);
        initial = draft && (!draft.language || draft.language === language) ? { code: draft.code, revisionId: problem.problemRevisionId, savedAt: 0 } : undefined;
        seqRef.current = draft?.seq ?? "1";
      } else initial = initial || readDraft(key) || undefined;
    } catch {
      setSaved("복원 실패 · 입력 내용은 복사해 보관해 주세요.");
    }
    codeRef.current =
      initial?.revisionId === problem.problemRevisionId
        ? initial.code
        : (problem.starterCodes[language] ?? "");
    setCode(codeRef.current);
    readyRef.current = true;
    setReady(true);
    setSaved(initial?.revisionId === problem.problemRevisionId ? "저장된 코드 복원 완료" : "초기 코드");
    const flush = () => {
      if (!readyRef.current) return;
      clearTimeout(timer.current);
      // 문제 전환·이탈로 자동 저장 타이머가 취소되면 시험 복원(readExamDraft)이 읽는 키에 바로 남기고 서버에도 보낸다.
      if (examId && examDirtyRef.current) {
        writeExamDraft(examId, problem.id, language, codeRef.current, seqRef.current);
        void persistExam();
      }
      const d = {
        code: codeRef.current,
        revisionId: problem.problemRevisionId,
        savedAt: Date.now(),
      };
      setDraft(key, d);
      try {
        saveDraft(key, d);
        setSaved("저장 완료");
      } catch {
        setSaved("저장 실패 · 코드를 복사해 보관해 주세요.");
      }
    };
    window.addEventListener("resolve:flush", flush);
    window.addEventListener("pagehide", flush);
    return () => {
      flush();
      window.removeEventListener("resolve:flush", flush);
      window.removeEventListener("pagehide", flush);
    };
    // 회원·시험·문제 회차·언어별 Buffer에서 자동 저장 전에 코드를 한 번 복원한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  function persist() {
    clearTimeout(timer.current);
    const d = {
      code: codeRef.current,
      revisionId: problem.problemRevisionId,
      savedAt: Date.now(),
    };
    setDraft(key, d);
    try {
      saveDraft(key, d);
      if (!examId) setSaved("저장 완료");
      return true;
    } catch {
      setSaved("저장 실패 · 코드를 복사해 보관해 주세요.");
      return false;
    }
  }
  async function persistExam() {
    if (!examId) return;
    try {
      const res = await contestApi.saveDraft(examId, problem.id, {
        language, sourceCode: codeRef.current, seq: seqRef.current,
      });
      if (res.applied) {
        examDirtyRef.current = false;
        seqRef.current = String(parseInt(res.serverSeq) + 1);
        writeExamDraft(examId, problem.id, language, codeRef.current, seqRef.current);
        setSaved("저장 완료");
      }
    } catch {}
  }
  function edit(value: string) {
    codeRef.current = value;
    examDirtyRef.current = true;
    setCode(value);
    setSaved("저장 중…");
    clearTimeout(timer.current);
    timer.current = setTimeout(() => { persist(); persistExam(); }, 500);
  }
  async function handleRun() {
    if (disabled || runDisabled) return;
    persist();
    setBusyRun(true); setShowRun(true);
    setJudgeError(null);
    setRunResult(null); setExamRunResult(null);
    setRunExpectations([]); setRunError(null);
    try {
      setRunExpectations(examId ? [] : runExpectedOutputs(problem.functionSpec.returnInputType, customInputs));
      if (examId) {
        const inputs = runInputs(problem.functionSpec.parameters, customInputs.map((row) => row.arguments), true, 10);
        setExamRunResult(await contestApi.runExam(examId, problem.id, { language, sourceCode: codeRef.current },
          inputs.map((item) => problem.functionSpec.parameters.map(({ name }) => item.argumentTexts[name]))));
        return;
      }
      setRunResult(
        await judgeApi.run({
          problemId: problem.id,
          problemRevisionId: problem.problemRevisionId,
          language,
          sourceCode: codeRef.current,
          useSamples: true,
          customInputs: examId ? [] : runInputs(problem.functionSpec.parameters, customInputs.map((row) => row.arguments), true),
        }),
      );
    } catch (e) {
      // 세션 만료는 결과 영역 문구가 아니라 버튼 동작(useAction)과 같게 로그인으로 보낸다.
      if (isUnauthorized(e)) return toLogin();
      const err = e as { message?: string };
      setRunError(err.message ?? "실행에 실패했습니다.");
    } finally {
      setBusyRun(false);
    }
  }
  async function handleSubmit(retry = false) {
    if (disabled) return;
    persist();
    await submitEditorCode({ workspace, submissionKey, problem, language, sourceCode: codeRef.current, seq: seqRef.current, examId, retry,
      onStart: () => { setShowRun(false); setRunResult(null); setRunError(null); setJudgeError(null); setPaused(true); },
      onAccepted: () => { if (!examId) { setPollVersion((value) => value + 1); setPaused(false); } router.refresh(); }, toLogin });
  }
  return (
    <>
      {judgeSubmissionId && polling && (
        <JudgePoll
          key={`${judgeSubmissionId}:${pollVersion}`}
          submissionId={judgeSubmissionId}
          onDone={(result, err) => {
            if (workspace.getState().activePracticeSubmissions[submissionKey] !== judgeSubmissionId) return;
            setPaused(true);
            if (err) setJudgeError(err);
            else if (result) workspace.getState().setPracticeResult(judgeSubmissionId, result);
          }}
        />
      )}
      <div className="editor-frame">
        {ready && (
          <EditorMonaco readOnly={disabled} code={code} language={language} fontSize={fontSize} onChange={edit} />
        )}
      </div>
      <section className="results stack-sm flex-1 max-h-[50dvh] overflow-y-auto overscroll-contain">
        <h3>실행 결과</h3>
        <EditorSubmissionStatus accepted={accepted} selected={attempt} pending={pending} pendingExam={!!pendingExam} error={requestError}
          disabled={busy || busyRun} hiddenResult={!showSubmission || showRun} paused={!showSubmission || paused} onRetry={() => handleSubmit(true)}
          onResume={() => { setView(submissionKey, { showSubmission: true }); setShowRun(false); setJudgeError(null); setPollVersion((value) => value + 1); setPaused(false); }}
          onSelect={(id) => {
            workspace.getState().selectPracticeSubmission(submissionKey, id);
            setView(submissionKey, { showSubmission: true });
            setShowRun(false); setJudgeError(null); setPollVersion((value) => value + 1); setPaused(false);
          }} />
        {showRun && examRunResult ? <ExamRunResult result={examRunResult} samples={problem.samples} /> : <EditorResultPanel busy={busy} sending={sending} submissionFailed={!showSubmission && (!!pending || !!pendingExam || !!requestError)}
          submissionPaused={showSubmission && !!judgeSubmissionId && !judgeResult && paused} showRun={showRun} busyRun={busyRun} queryError={judgeError}
          judgeResult={showSubmission ? judgeResult : null} runError={runError} runResult={runResult} runExpectations={runExpectations} />}
      </section>
      <EditorActions saved={examId && view?.acceptedSourceCode === code ? "제출 완료" : saved} ready={ready && !disabled} busy={busy} busyRun={busyRun} runDisabled={runDisabled}
        pendingSubmission={!!pending || !!pendingExam || (!!attempt && !attempt.result)} onReset={() => setReset(true)}
        onTestCases={() => setTestCasesOpen(true)}
        onRun={handleRun} onSubmit={() => handleSubmit()} />
      <Modal open={testCasesOpen} onOpenChange={setTestCasesOpen}
        title="테스트 케이스" description="공개 예제와 직접 입력한 값으로 실행합니다. 제출 시에는 전체 테스트로 채점합니다.">
        <EditorRunInputs parameters={problem.functionSpec.parameters} returnType={problem.functionSpec.returnInputType}
          rows={customInputs} disabled={disabled || busy || busyRun}
          onRows={setCustomInputs} storageError={runStorageError} maxCases={examId ? 10 : 5} allowExpectations={!examId} />
      </Modal>
      <EditorResetDialog open={reset} onOpenChange={setReset}
        onReset={() => { edit(problem.starterCodes[language] ?? ""); setReset(false); }} />
    </>
  );
}
