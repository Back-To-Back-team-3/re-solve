"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button, Modal, useLoginRedirect } from "@/components/ui";
import { contestApi, isUnauthorized, judgeApi } from "@/services/api";
import { runInputs, runExpectedOutputs, type RunTestCase } from "@/services/run-inputs";
import { draftKey, readDraft, saveDraft, useWorkspace, useWorkspaceApi } from "@/stores/workspace";
import type { Language, Problem, RunResponse } from "@/services/types";
import { JudgePoll, EditorResultPanel } from "@/features/editor-results";
import { EditorMonaco } from "@/features/editor-monaco";
import { EditorRunInputs } from "@/features/editor-run-inputs";
import { EditorActions } from "@/features/editor-actions";
import { EditorSubmissionStatus } from "@/features/editor-submission-status";
import { expiredPracticeRequestMessage, isPracticeRequestExpired } from "@/services/practice-request";
import { readExamDraft, writeExamDraft } from "@/services/exam-editor-draft";
export function Buffer({
  problem,
  userId,
  examId,
  language,
  fontSize,
}: {
  problem: Problem;
  userId: string;
  examId?: string;
  language: Language;
  fontSize: number;
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
    [sending, setSending] = useState(false),
    [paused, setPaused] = useState(false),
    [pollVersion, setPollVersion] = useState(0),
    [runResult, setRunResult] = useState<RunResponse | null>(null),
    [runExpectations, setRunExpectations] = useState<(string | null)[]>([]),
    [runError, setRunError] = useState<string | null>(null),
    [busyRun, setBusyRun] = useState(false),
    [showRun, setShowRun] = useState(false),
    [judgeError, setJudgeError] = useState<string | null>(null),
    [requestError, setRequestError] = useState<string | null>(null);
  const runInputKey = JSON.stringify([userId, examId ?? "practice", problem.id, problem.problemRevisionId]);
  const runConfiguration = useWorkspace((state) => state.practiceRunInputs[runInputKey]);
  const setRunInputs = useWorkspace((state) => state.setPracticeRunInputs);
  const useSamples = runConfiguration?.useSamples ?? true;
  const customInputs = runConfiguration?.customInputs ?? [];
  const setUseSamples = (value: boolean) => setRunInputs(runInputKey, { useSamples: value });
  const setCustomInputs = (value: RunTestCase[]) => setRunInputs(runInputKey, { customInputs: value });
  const submissionKey = JSON.stringify([key, problem.problemRevisionId]);
  const submissions = useWorkspace((state) => state.practiceSubmissions);
  const pending = useWorkspace((state) => state.practiceRequests[submissionKey]);
  const activeId = useWorkspace((state) => state.activePracticeSubmissions[submissionKey]);
  const attempt = activeId ? submissions[activeId] : undefined;
  const accepted = Object.values(submissions).flatMap((item) => item?.contextKey === submissionKey ? [item] : []);
  const workspace = useWorkspaceApi();
  const sendingRef = useRef(false), router = useRouter(), toLogin = useLoginRedirect();
  const judgeSubmissionId = attempt?.submissionId ?? null;
  const judgeResult = attempt?.result ?? null;
  const polling = !!judgeSubmissionId && !judgeResult && !paused;
  const busy = sending || polling;
  useEffect(() => {
    let initial: typeof memory | undefined = memory;
    try {
      if (examId) {
        const draft = readExamDraft(examId, problem.id);
        initial = draft ? { code: draft.code, revisionId: problem.problemRevisionId, savedAt: 0 } : undefined;
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
    persist();
    setBusyRun(true);
    setShowRun(true);
    setRunResult(null);
    setRunExpectations([]);
    setRunError(null);
    try {
      setRunExpectations(examId ? [] : runExpectedOutputs(problem.functionSpec.returnInputType, customInputs));
      setRunResult(
        await judgeApi.run({
          problemId: problem.id,
          problemRevisionId: problem.problemRevisionId,
          language,
          sourceCode: codeRef.current,
          useSamples: examId ? true : useSamples,
          customInputs: examId ? [] : runInputs(problem.functionSpec.parameters, customInputs.map((row) => row.arguments), useSamples),
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
    if (sendingRef.current) return;
    persist();
    if (retry && !pending) return;
    const request = retry && pending ? pending : {
      idempotencyKey: crypto.randomUUID(),
      createdAt: Date.now(),
      body: {
        problemId: problem.id,
        problemRevisionId: problem.problemRevisionId,
        language,
        sourceCode: codeRef.current,
      },
    };
    if (retry && isPracticeRequestExpired(request.createdAt, Date.now())) {
      setRequestError(expiredPracticeRequestMessage); return;
    }
    sendingRef.current = true;
    setShowRun(false);
    if (!examId) workspace.getState().setPracticeRequest(submissionKey, request);
    setRunResult(null);
    setRunError(null);
    setJudgeError(null);
    setRequestError(null);
    setPaused(true);
    setSending(true);
    try {
      if (examId) {
        await contestApi.submitExam(examId, { problemId: problem.id, language, sourceCode: codeRef.current, seq: seqRef.current });
        setSaved("제출 완료");
      } else {
        const receipt = await judgeApi.submit(request.body, request.idempotencyKey);
        if (!workspace.getState().acceptPracticeSubmission(submissionKey, request, receipt.submissionId)) return;
        setPollVersion((value) => value + 1);
        setPaused(false);
      }
      router.refresh();
    } catch (failure) {
      if (isUnauthorized(failure)) return toLogin();
      const message = typeof failure === "object" && failure !== null && "message" in failure
        && typeof failure.message === "string" ? failure.message : "제출에 실패했습니다.";
      setRequestError(message);
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
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
          <EditorMonaco code={code} language={language} fontSize={fontSize} onChange={edit} />
        )}
      </div>
      <section className="results stack-sm flex-1">
        <h3>실행 결과</h3>
        <EditorSubmissionStatus accepted={accepted} selected={attempt} pending={pending} error={requestError}
          disabled={busy || busyRun} paused={paused} onRetry={() => handleSubmit(true)}
          onResume={() => { setShowRun(false); setJudgeError(null); setPollVersion((value) => value + 1); setPaused(false); }}
          onSelect={(id) => {
            workspace.getState().selectPracticeSubmission(submissionKey, id);
            setShowRun(false); setJudgeError(null); setPollVersion((value) => value + 1); setPaused(false);
          }} />
        <EditorResultPanel busy={busy} showRun={showRun} busyRun={busyRun} queryError={judgeError}
          judgeResult={judgeResult} runError={runError} runResult={runResult} runExpectations={runExpectations} />
      </section>
      <EditorActions saved={saved} ready={ready} busy={busy} busyRun={busyRun}
        pendingSubmission={!!pending || (!!attempt && !attempt.result)} onReset={() => setReset(true)}
        onTestCases={examId ? undefined : () => setTestCasesOpen(true)}
        onRun={handleRun} onSubmit={() => handleSubmit()} />
      {!examId && <Modal open={testCasesOpen} onOpenChange={setTestCasesOpen}
        title="테스트 케이스" description="공개 예제와 직접 입력한 값으로 실행합니다. 제출 시에는 전체 테스트로 채점합니다.">
        <EditorRunInputs parameters={problem.functionSpec.parameters} returnType={problem.functionSpec.returnInputType}
          rows={customInputs} useSamples={useSamples} disabled={busy || busyRun}
          onRows={setCustomInputs} onSamples={setUseSamples} />
        <Button className="primary" onClick={() => setTestCasesOpen(false)}>적용</Button>
      </Modal>}
      <Modal
        open={reset}
        onOpenChange={setReset}
        title="코드를 초기화할까요?"
        description="현재 언어의 작성 내용을 초기 코드로 바꿉니다."
      >
        <Button
          className="danger"
          onClick={() => {
            edit(problem.starterCodes[language] ?? "");
            setReset(false);
          }}
        >
          초기화
        </Button>
      </Modal>
    </>
  );
}
