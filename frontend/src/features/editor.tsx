"use client";
import dynamic from "next/dynamic";
import { useEffect, useRef, useState } from "react";
import { loader } from "@monaco-editor/react";
import type { editor } from "monaco-editor";
import { RotateCcw, Save, Play, Send } from "lucide-react";
import {
  Button,
  Badge,
  Modal,
  Empty,
  useAction,
  useToast,
} from "@/components/ui";
import { api, labels } from "@/services/mock-api";
import {
  draftKey,
  readDraft,
  saveDraft,
  useWorkspace,
} from "@/stores/workspace";
import type { Language, Problem, Submission } from "@/services/types";
const Monaco = dynamic(() => import("@monaco-editor/react"), {
  ssr: false,
  loading: () => <div className="skeleton pixel">LOADING EDITOR...</div>,
});
loader.config({ paths: { vs: "/monaco/vs" } });
export function CodeEditor({
  problem,
  userId,
  examId,
}: {
  problem: Problem;
  userId: string;
  examId?: string;
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
            value={language}
            onChange={(e) => settings(e.target.value as Language, fontSize)}
          >
            {(["python3", "java", "cpp"] as const).map((l) => (
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
          onChange={(e) => settings(language, Number(e.target.value))}
        >
          {[12, 14, 16, 18, 20, 22].map((n) => (
            <option key={n} value={n}>
              {n}px
            </option>
          ))}
        </select>
      </div>
      <Buffer
        key={draftKey(userId, problem.id, language, examId)}
        problem={problem}
        userId={userId}
        examId={examId}
        language={language}
        fontSize={fontSize}
      />
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
    timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [code, setCode] = useState(""),
    [ready, setReady] = useState(false),
    [saved, setSaved] = useState("복원 중…"),
    [reset, setReset] = useState(false),
    [submission, setSubmission] = useState<Submission | null>(null),
    [busy, setBusy] = useState(false);
  const action = useAction(),
    toast = useToast();
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  useEffect(() => {
    let initial = memory;
    try {
      initial = initial || readDraft(key) || undefined;
    } catch {
      setSaved("복원 실패 · 입력 내용은 복사해 보관해 주세요.");
    }
    codeRef.current =
      initial?.revisionId === problem.problemRevisionId
        ? initial.code
        : problem.starterCodes[language];
    setCode(codeRef.current);
    readyRef.current = true;
    setReady(true);
    setSaved(initial ? "저장된 코드 복원 완료" : "초기 코드");
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
      setSaved("저장 완료");
      return true;
    } catch {
      setSaved("저장 실패 · 코드를 복사해 보관해 주세요.");
      return false;
    }
  }
  function edit(value: string) {
    codeRef.current = value;
    setCode(value);
    setSaved("저장 중…");
    clearTimeout(timer.current);
    timer.current = setTimeout(persist, 500);
  }
  useEffect(() => {
    if (!submission || ["COMPLETED", "FAILED"].includes(submission.state))
      return;
    const interval = setInterval(
      () =>
        api
          .settle(submission.id)
          .then(setSubmission)
          .catch(() => {
            clearInterval(interval);
            setBusy(false);
            toast("채점 상태를 저장하지 못했습니다.");
          }),
      300,
    );
    return () => clearInterval(interval);
  }, [submission, toast]);
  useEffect(() => {
    if (submission && ["COMPLETED", "FAILED"].includes(submission.state))
      setBusy(false);
  }, [submission]);
  async function judge(kind: "run" | "submit") {
    persist();
    setBusy(true);
    const ok = await action(async () =>
      setSubmission(
        await api.submit({
          problemId: problem.id,
          language,
          sourceCode: codeRef.current,
          kind,
          examId,
        }),
      ),
    );
    if (!ok) setBusy(false);
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
            disabled={busy || !ready}
            onClick={() => judge("run")}
          >
            <Play size={14} />
            실행
          </Button>
          <Button
            className="small primary"
            disabled={busy || !ready}
            onClick={() => judge("submit")}
          >
            <Send size={14} />
            제출
          </Button>
        </div>
      </div>
      <section className="results stack-sm">
        <div className="row between">
          <h3>실행 결과</h3>
          <Badge tone="warning">모의 결과</Badge>
        </div>
        <p className="muted">
          코드를 실제로 평가하지 않습니다. 데모 설정의 시나리오를 반환합니다.
        </p>
        {!submission ? (
          <Empty
            title="코드를 실행해 보세요"
            description="공개 예제 실행 또는 전체 제출을 선택할 수 있어요."
          />
        ) : (
          <>
            <div className="row">
              <Badge
                tone={
                  submission.verdict === "AC"
                    ? "success"
                    : submission.verdict || submission.state === "FAILED"
                      ? "error"
                      : "pending"
                }
              >
                {labels[submission.verdict || submission.state]}
              </Badge>
              <span>
                {submission.kind === "run" ? "공개 예제 실행" : "전체 제출"} ·{" "}
                {submission.passedCount} / {submission.totalCount}
              </span>
            </div>
            {submission.state === "COMPLETED" &&
              Array.from({ length: submission.totalCount }, (_, i) => (
                <div className="result-case" key={i}>
                  <div>
                    <b>
                      {submission.kind === "run" ? "공개 예제" : "숨김 테스트"}{" "}
                      {i + 1}
                    </b>
                    {submission.kind === "run" && (
                      <p className="mono">
                        입력 {problem.samples[i].input} → 기대값{" "}
                        {problem.samples[i].expected}
                      </p>
                    )}
                  </div>
                  <Badge
                    tone={i < submission.passedCount ? "success" : "error"}
                  >
                    {i < submission.passedCount
                      ? "통과"
                      : labels[submission.verdict || "WA"]}
                  </Badge>
                </div>
              ))}
            {submission.state === "FAILED" && (
              <p className="error-text">
                모의 채점 시스템 오류입니다. 시나리오를 변경한 후 다시 제출해
                주세요.
              </p>
            )}
          </>
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
            edit(problem.starterCodes[language]);
            setReset(false);
          }}
        >
          초기화
        </Button>
      </Modal>
    </>
  );
}
