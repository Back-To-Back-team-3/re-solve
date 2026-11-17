"use client";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { actionErrorMessage, diagnosisApi, isUnauthorized } from "@/services/api";
import { diagnosisCodeError } from "@/services/diagnosis";
import { toEditorProblem } from "@/services/problem-editor";
import { runInputs, type RunTestCase } from "@/services/run-inputs";
import type { DiagnosisContent, DiagnosisRunResult, Language } from "@/services/types";
import { Button, Modal, useLoginRedirect } from "@/components/ui";
import { EditorRunInputs } from "@/features/editor-run-inputs";

/** 진단 실행은 현재 코드의 스냅샷만 보내며 저장·제출·레벨 갱신을 호출하지 않는다. */
export function DiagnosisRun({ diagnosisId, content, language, sourceCode, disabled, canSubmit, pending, onSave, onSubmit }: {
  diagnosisId: string; content: DiagnosisContent; language: Language; sourceCode: string;
  disabled: boolean; canSubmit: boolean; pending: boolean; onSave: () => void; onSubmit: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<RunTestCase[]>([]);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<DiagnosisRunResult | null>(null);
  const [error, setError] = useState("");
  const mounted = useRef(true);
  const running = useRef(false);
  const enabled = useRef(!disabled);
  useLayoutEffect(() => { enabled.current = !disabled; }, [disabled]);
  const toLogin = useLoginRedirect();
  const parameters = toEditorProblem(content).functionSpec.parameters;
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  async function run() {
    if (running.current || disabled || !canSubmit) return;
    setError(""); setResult(null);
    const invalid = diagnosisCodeError(sourceCode, true);
    if (invalid) { setError(invalid); return; }
    running.current = true; setBusy(true);
    try {
      const inputs = runInputs(parameters, rows.map((row) => row.arguments), true, 10);
      const response = await diagnosisApi.run(diagnosisId, content.problemId, { language, sourceCode },
        inputs.map((item) => parameters.map(({ name }) => item.argumentTexts[name])));
      // 문항·언어 전환 또는 종료 뒤 도착한 결과는 새 화면에 표시하지 않는다.
      if (mounted.current && enabled.current) setResult(response);
    } catch (failure) {
      if (!mounted.current || !enabled.current) return;
      if (isUnauthorized(failure)) { toLogin(); return; }
      setError(actionErrorMessage(failure));
    } finally {
      running.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  return <>
    {(busy || result || error) && <section className="results stack-sm" aria-label="진단 Run 실행 결과">
      <h3>실행 결과</h3>
      <small>공개 예제·직접 입력의 실행 결과이며 진단 점수·레벨에 반영되지 않습니다.</small>
      {busy && <p role="status">실행 중…</p>}
      {error && <p role="alert" className="error-text">{error}</p>}
      {result?.results.map((item) => <article className="card stack-sm" key={`${item.type}:${item.index}`}>
        <h4>{item.type === "SAMPLE" ? "공개 예제" : "직접 입력"} {item.index + 1}</h4>
        {item.type === "SAMPLE" && <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">기대값: {content.samples[item.index]?.expectedReturnText ?? "—"}</pre>}
        <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">실제 반환값: {item.output ?? "—"}</pre>
        {item.error && <pre role="alert" className="error-text whitespace-pre-wrap [overflow-wrap:anywhere]">{item.error}</pre>}
        <p>실행 시간: {item.timeMs}ms · 메모리: {item.memoryKb}KiB</p>
        {item.stdout && <div><h5>디버그 출력</h5><pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">{item.stdout}</pre></div>}
      </article>)}
    </section>}
    <div className="editor-actions">
      <div className="row wrap">
        <Button onClick={() => setOpen(true)} disabled={disabled || busy}>테스트 케이스 추가하기</Button>
        <Button onClick={onSave} disabled={disabled}>지금 저장</Button>
      </div>
      <div className="row wrap">
        <Button onClick={() => void run()} disabled={disabled || busy || !canSubmit}>{busy ? "실행 중…" : "실행"}</Button>
        <Button className="primary" onClick={onSubmit} disabled={disabled || !canSubmit}>
          {pending ? "같은 제출 재요청" : "문항 제출"}
        </Button>
      </div>
    </div>
    <Modal open={open} onOpenChange={setOpen} title="테스트 케이스"
      description="공개 예제와 직접 입력만 실행합니다. 실행 결과는 진단 점수·레벨에 반영되지 않습니다.">
      <EditorRunInputs parameters={parameters} useSamples rows={rows} disabled={busy || disabled}
        onSamples={() => {}} onRows={setRows} maxCases={10} samplesRequired allowExpectations={false} />
      <Button className="primary" onClick={() => setOpen(false)}>적용</Button>
    </Modal>
  </>;
}
