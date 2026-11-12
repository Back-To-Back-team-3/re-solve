"use client";
import dynamic from "next/dynamic";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { loader } from "@monaco-editor/react";
import type { editor } from "monaco-editor";
import { RotateCcw, Save, Play, Send } from "lucide-react";
import {
  Button,
  Badge,
  Modal,
  Empty,
} from "@/components/ui";
import { labels } from "@/services/mock-api";
import { judgeApi, contestApi } from "@/services/api";
import { usePollUntil } from "@/services/hooks";
import {
  draftKey,
  readDraft,
  saveDraft,
  useWorkspace,
} from "@/stores/workspace";
import type { JudgeResult, Language, Problem, RunResponse } from "@/services/types";
const Monaco = dynamic(() => import("@monaco-editor/react"), {
  ssr: false,
  loading: () => <div className="skeleton pixel">LOADING EDITOR...</div>,
});
loader.config({ paths: { vs: "/monaco/vs" } });
export function CodeEditor({
  problem,
  userId,
  examId,
  availableLanguages,
}: {
  problem: Problem;
  userId: string;
  examId?: string;
  availableLanguages?: Language[];
}) {
  const language = useWorkspace((s) => s.language),
    setLanguage = useWorkspace((s) => s.setLanguage),
    fontSize = useWorkspace((s) => s.fontSize),
    setFontSize = useWorkspace((s) => s.setFontSize);
  useEffect(() => {
    try {
      const stored = localStorage.getItem("resolve:editor");
      if (stored) {
        const data = JSON.parse(stored);
        if (["java", "python3", "cpp"].includes(data.language))
          setLanguage(data.language);
        if (data.fontSize >= 12 && data.fontSize <= 22)
          setFontSize(data.fontSize);
      }
    } catch {}
  }, [setLanguage, setFontSize]);
  const languages = availableLanguages ?? (["python3", "java", "cpp"] as const)
    .filter((value) => typeof problem.starterCodes[value] === "string");
  const selectedLanguage = languages.includes(language) ? language : languages[0];
  function settings(lang: Language, size: number) {
    window.dispatchEvent(new Event("resolve:flush"));
    setLanguage(lang);
    setFontSize(size);
    try {
      localStorage.setItem(
        "resolve:editor",
        JSON.stringify({ language: lang, fontSize: size }),
      );
    } catch {}
  }
  return (
    <div className="code-panel">
      <div className="editor-toolbar">
        <div className="row">
          <span className="pixel">CODE EDITOR</span>
          <select
            aria-label="코드 언어"
            className="input"
            value={selectedLanguage ?? ""}
            disabled={!selectedLanguage}
            onChange={(e) => settings(e.target.value as Language, fontSize)}
          >
            {languages.map((l) => (
              <option key={l} value={l}>
                {labels[l]}
              </option>
            ))}
          </select>
        </div>
        <select
          aria-label="글자 크기"
          className="input"
          value={fontSize}
          onChange={(e) => settings(selectedLanguage ?? language, Number(e.target.value))}
        >
          {[12, 14, 16, 18, 20, 22].map((n) => (
            <option key={n} value={n}>
              {n}px
            </option>
          ))}
        </select>
      </div>
      {selectedLanguage ? <Buffer
        key={JSON.stringify([draftKey(userId, problem.id, selectedLanguage, examId), problem.problemRevisionId])}
        problem={problem}
        userId={userId}
        examId={examId}
        language={selectedLanguage}
        fontSize={fontSize}
      /> : <Empty title="실행 가능한 언어가 없어요" description="지원 실행 환경을 확인한 뒤 다시 시도해 주세요." />}
    </div>
  );
}
function JudgePoll({
  submissionId,
  onDone,
}: {
  submissionId: string;
  onDone: (result: JudgeResult | null, error: string | null) => void;
}) {
  const onDoneRef = useRef(onDone);
  useLayoutEffect(() => {
    onDoneRef.current = onDone;
  });
  const { data, loading, error, polling } = usePollUntil(
    (signal) => judgeApi.getStatus(submissionId, signal),
    (d) => d.status === "COMPLETED" || d.status === "FAILED",
    { intervalMs: 2000, subsequentIntervalMs: 5000, timeoutMs: 60000 },
  );
  useEffect(() => {
    if (loading || polling) return;
    if (error) { onDoneRef.current(null, error.message); return; }
    if (data) onDoneRef.current(data, null);
  }, [loading, polling, data, error]);
  return null;
}
const VERDICT_LABELS: Record<string, string> = {
  AC: "정답", WA: "오답", TLE: "시간 초과",
  MLE: "메모리 초과", RE: "런타임 에러", CE: "컴파일 에러",
};
function verdictTone(verdict: string | null): string {
  if (!verdict) return "pending";
  if (verdict === "AC") return "success";
  if (verdict === "WA" || verdict === "RE") return "error";
  return "warning";
}
function JudgeResultView({ result }: { result: JudgeResult }) {
  const v = result.effectiveResult?.verdict ?? null;
  const r = result.effectiveResult;
  return (
    <div className="stack-sm">
      <div className="row">
        <Badge tone={verdictTone(v)}>
          {v
            ? `${v} ${VERDICT_LABELS[v] ?? v}`
            : labels[result.status]}
        </Badge>
        {r && (
          <span>
            {r.passedCount} / {r.totalCount} 통과
            {r.maxTimeMs != null && ` · ${r.maxTimeMs}ms`}
            {r.maxMemoryKb != null &&
              ` · ${Math.round(r.maxMemoryKb / 1024)}MB`}
          </span>
        )}
      </div>
      {result.compileMessage && (
        <pre className="error-text">{result.compileMessage}</pre>
      )}
    </div>
  );
}
function RunResult({ result }: { result: RunResponse }) {
  if (result.compileMessage) {
    return <pre className="error-text">{result.compileMessage}</pre>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>결과</th>
            <th>출력</th>
          </tr>
        </thead>
        <tbody>
          {result.results.map((t, i) => (
            <tr key={i}>
              <td>{i + 1}</td>
              <td>
                <Badge
                  tone={
                    t.verdict === "AC"
                      ? "success"
                      : t.verdict
                        ? "error"
                        : ""
                  }
                >
                  {t.verdict === "AC"
                    ? "통과"
                    : t.verdict
                      ? labels[t.verdict]
                      : "실행"}
                </Badge>
              </td>
              <td className="mono">{t.actualReturnText ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Buffer({
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
    timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [code, setCode] = useState(""),
    [ready, setReady] = useState(false),
    [saved, setSaved] = useState("복원 중…"),
    [reset, setReset] = useState(false),
    [sending, setSending] = useState(false),
    [paused, setPaused] = useState(false),
    [pollVersion, setPollVersion] = useState(0),
    [runResult, setRunResult] = useState<RunResponse | null>(null),
    [runError, setRunError] = useState<string | null>(null),
    [busyRun, setBusyRun] = useState(false),
    [showRun, setShowRun] = useState(false),
    [judgeError, setJudgeError] = useState<string | null>(null);
  const submissionKey = JSON.stringify([key, problem.problemRevisionId]);
  const attempt = useWorkspace((state) => state.practiceSubmissions[submissionKey]);
  const setAttempt = useWorkspace((state) => state.setPracticeSubmission);
  const sendingRef = useRef(false);
  const judgeSubmissionId = attempt?.submissionId ?? null;
  const judgeResult = attempt?.result ?? null;
  const polling = !!judgeSubmissionId && !judgeResult && !paused;
  const busy = sending || polling;
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  useEffect(() => {
    if (examId) {
      try {
        const raw = localStorage.getItem(
          `re-solve:editor:draft:exam:${examId}:${problem.id}`,
        );
        if (raw) {
          const d = JSON.parse(raw);
          if (typeof d.code === "string") codeRef.current = d.code;
          if (typeof d.seq === "string") seqRef.current = d.seq;
          setSaved("저장된 코드 복원 완료");
        } else {
          codeRef.current = problem.starterCodes[language] ?? "";
          setSaved("초기 코드");
        }
      } catch {
        codeRef.current = problem.starterCodes[language] ?? "";
        setSaved("초기 코드");
      }
      setCode(codeRef.current);
    } else {
      let initial = memory;
      try {
        initial = initial || readDraft(key) || undefined;
      } catch {
        setSaved("복원 실패 · 입력 내용은 복사해 보관해 주세요.");
      }
      codeRef.current =
        initial?.revisionId === problem.problemRevisionId
          ? initial.code
          : (problem.starterCodes[language] ?? "");
      setCode(codeRef.current);
      setSaved(initial?.revisionId === problem.problemRevisionId ? "저장된 코드 복원 완료" : "초기 코드");
    }
    readyRef.current = true;
    setReady(true);
    const flush = () => {
      if (!readyRef.current) return;
      clearTimeout(timer.current);
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
    // Buffer is keyed by user, exam, problem and language; hydrate exactly once before autosave.
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
        language,
        sourceCode: codeRef.current,
        seq: seqRef.current,
      });
      if (res.applied) {
        seqRef.current = String(parseInt(res.serverSeq) + 1);
        try {
          localStorage.setItem(
            `re-solve:editor:draft:exam:${examId}:${problem.id}`,
            JSON.stringify({ language, code: codeRef.current, seq: seqRef.current }),
          );
        } catch {}
        setSaved("저장 완료");
      }
    } catch {}
  }
  function edit(value: string) {
    codeRef.current = value;
    setCode(value);
    setSaved("저장 중…");
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      persist();
      persistExam();
    }, 500);
  }
  async function handleRun() {
    persist();
    setBusyRun(true);
    setShowRun(true);
    setRunResult(null);
    setRunError(null);
    try {
      setRunResult(
        await judgeApi.run({
          problemId: problem.id,
          problemRevisionId: problem.problemRevisionId,
          language,
          sourceCode: codeRef.current,
          useSamples: true,
          customInputs: [],
        }),
      );
    } catch (e) {
      const err = e as { message?: string };
      setRunError(err.message ?? "실행에 실패했습니다.");
    } finally {
      setBusyRun(false);
    }
  }
  async function handleSubmit(retry = false) {
    if (sendingRef.current) return;
    persist();
    if (examId) {
      sendingRef.current = true;
      setSending(true);
      setShowRun(false);
      setRunResult(null);
      setRunError(null);
      setJudgeError(null);
      try {
        await contestApi.submitExam(examId, {
          problemId: problem.id, language, sourceCode: codeRef.current, seq: seqRef.current,
        });
        setSaved("제출 완료");
      } catch (failure) {
        const error = failure as { message?: string };
        setJudgeError(error.message ?? "제출에 실패했습니다.");
      } finally {
        sendingRef.current = false;
        setSending(false);
      }
      return;
    }
    const request = retry && attempt && !attempt.submissionId ? attempt : {
      idempotencyKey: crypto.randomUUID(),
      body: {
        problemId: problem.id,
        problemRevisionId: problem.problemRevisionId,
        language,
        sourceCode: codeRef.current,
      },
      submissionId: null,
      result: null,
    };
    sendingRef.current = true;
    setShowRun(false);
    setAttempt(submissionKey, request);
    setRunResult(null);
    setRunError(null);
    setJudgeError(null);
    setPaused(true);
    setSending(true);
    try {
      const receipt = await judgeApi.submit(request.body, request.idempotencyKey);
      setAttempt(submissionKey, { ...request, submissionId: receipt.submissionId });
      setPollVersion((value) => value + 1);
      setPaused(false);
    } catch (failure) {
      const message = typeof failure === "object" && failure !== null && "message" in failure
        && typeof failure.message === "string" ? failure.message : "제출에 실패했습니다.";
      setJudgeError(message);
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }
  function theme(monaco: typeof import("monaco-editor")) {
    const s = getComputedStyle(document.documentElement),
      color = (name: string) => s.getPropertyValue(name).trim();
    monaco.editor.defineTheme("resolve", {
      base: "vs-dark",
      inherit: true,
      rules: [],
      colors: {
        "editor.background": color("--editor-bg"),
        "editor.foreground": color("--editor-text"),
        "editorLineNumber.foreground": color("--editor-muted"),
        "editorCursor.foreground": color("--primary"),
      },
    });
    monaco.editor.setTheme("resolve");
  }
  return (
    <>
      {judgeSubmissionId && polling && (
        <JudgePoll
          key={`${judgeSubmissionId}:${pollVersion}`}
          submissionId={judgeSubmissionId}
          onDone={(result, err) => {
            setPaused(true);
            if (err) setJudgeError(err);
            else if (attempt && result) setAttempt(submissionKey, { ...attempt, result });
          }}
        />
      )}
      <div className="editor-frame">
        {ready && (
          <Monaco
            height="100%"
            language={language === "python3" ? "python" : language}
            value={code}
            theme="resolve"
            beforeMount={theme}
            onMount={(ed, m) => {
              editorRef.current = ed;
              const observer = new MutationObserver(() => theme(m));
              observer.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ["data-theme"],
              });
              ed.onDidDispose(() => observer.disconnect());
            }}
            onChange={(v) => edit(v || "")}
            options={{
              // Prefer the stable textarea path: experimental EditContext dropped rapid key events in Chromium.
              editContext: false,
              fontSize,
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              automaticLayout: true,
              tabSize: 4,
              padding: { top: 20 },
              ariaLabel: "코드 편집기",
              wordWrap: "on",
            }}
          />
        )}
      </div>
      <div className="editor-actions">
        <span
          role="status"
          className={saved.startsWith("저장 실패") ? "error-text" : ""}
        >
          {saved}
        </span>
        <div className="row">
          <Button className="small" aria-label="코드 저장" onClick={persist}>
            <Save size={15} />
          </Button>
          <Button
            className="small"
            aria-label="코드 초기화"
            onClick={() => setReset(true)}
          >
            <RotateCcw size={15} />
          </Button>
          <Button
            className="small"
            disabled={busyRun || busy || !ready}
            onClick={handleRun}
          >
            <Play size={14} />
            실행
          </Button>
          <Button
            className="small primary"
            disabled={busy || busyRun || !ready}
            onClick={() => handleSubmit()}
          >
            <Send size={14} />
            {busy ? "채점 중…" : attempt && !attempt.result ? "현재 코드로 새 제출" : "제출"}
          </Button>
        </div>
      </div>
      <section className="results stack-sm">
        <h3>실행 결과</h3>
        {busy ? (
          <div className="row">
            <Badge tone="pending">채점 중…</Badge>
          </div>
        ) : !showRun && judgeError ? (
          <p className="error-text">{judgeError}</p>
        ) : !showRun && judgeResult ? (
          <JudgeResultView result={judgeResult} />
        ) : busyRun ? (
          <p className="muted">실행 중…</p>
        ) : runError ? (
          <p className="error-text">{runError}</p>
        ) : runResult ? (
          <RunResult result={runResult} />
        ) : (
          <Empty
            title="코드를 실행해 보세요"
            description="공개 예제 실행 또는 전체 제출을 선택할 수 있어요."
          />
        )}
        {attempt && !attempt.result && (paused || !attempt.submissionId) && !busy && !busyRun && (
          <div className="stack-sm">
            {attempt.submissionId ? (
              <>
                <p className="muted">제출 #{attempt.submissionId}의 결과를 다시 확인할 수 있어요.</p>
                <Button className="small" onClick={() => {
                  setShowRun(false);
                  setJudgeError(null);
                  setPollVersion((value) => value + 1);
                  setPaused(false);
                }}>채점 상태 다시 조회</Button>
              </>
            ) : (
              <>
                <p className="muted">재요청은 원래 제출한 코드와 요청 키를 유지합니다.</p>
                <Button className="small" onClick={() => handleSubmit(true)}>같은 제출 재요청</Button>
              </>
            )}
          </div>
        )}
      </section>
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
