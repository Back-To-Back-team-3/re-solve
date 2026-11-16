"use client";
import { useEffect } from "react";
import { useResource } from "@/services/hooks";
import { diagnosisApi, judgeApi } from "@/services/api";
import { isDiagnosisLanguage, compareDiagnosisSeq } from "@/services/diagnosis";
import { availableProblemLanguages, toEditorProblem } from "@/services/problem-editor";
import { labelOf, languageLabel } from "@/services/labels";
import { useDiagnosis } from "@/stores/diagnosis";
import { useWorkspace } from "@/stores/workspace";
import { EditorMonaco } from "@/features/editor-monaco";
import { ProblemDescription } from "@/features/problem";
import { Button, Empty, ErrorState, Loading } from "@/components/ui";
import type { DiagnosisContent, JudgeLanguage } from "@/services/types";

function DiagnosisBuffer({ content, runtimes, disabled, onSubmit, onSave }: {
  content: DiagnosisContent; runtimes: JudgeLanguage[]; disabled: boolean;
  onSubmit: () => void; onSave: () => void;
}) {
  const { buffers, ensure, edit, activeProblem } = useDiagnosis();
  const fontSize = useWorkspace((state) => state.fontSize);
  const setFontSize = useWorkspace((state) => state.setFontSize);
  const languages = availableProblemLanguages(content, runtimes);
  const buffer = buffers[activeProblem];
  const draft = buffer?.draft;
  const first = languages[0];
  useEffect(() => {
    if (first) ensure(content.problemId, first, content.starterCodes.find((code) => code.language === first)?.sourceCode ?? "");
  }, [content, ensure, first]);
  const saved = draft && compareDiagnosisSeq(draft.seq, buffer.savedSeq) <= 0;
  return <div className="code-panel">
    <div className="editor-toolbar row wrap">
      <span className="pixel">CODE EDITOR</span>
      <select className="input" aria-label="코드 언어" value={draft?.language ?? first ?? ""} disabled={disabled || !first}
        onChange={(event) => {
          const language = event.target.value;
          if (isDiagnosisLanguage(language)) edit(content.problemId, language, buffer.codes[language] ?? content.starterCodes.find((code) => code.language === language)?.sourceCode ?? "");
        }}>
        {draft && !languages.includes(draft.language) && <option value={draft.language}>{labelOf(languageLabel, draft.language)} (현재 사용 불가)</option>}
        {languages.map((language) => <option key={language} value={language}>{labelOf(languageLabel, language)}</option>)}
      </select>
      <select className="input" aria-label="글자 크기" value={fontSize} onChange={(event) => setFontSize(Number(event.target.value))}>
        {[12, 14, 16, 18, 20, 22].map((size) => <option key={size} value={size}>{size}px</option>)}
      </select>
    </div>
    {!first ? <Empty title="실행 가능한 언어가 없어요" description="지원 실행 환경을 확인한 뒤 다시 시도해 주세요." /> : draft && <>
      <div className="editor-frame">
        <EditorMonaco key={`${content.problemId}:${draft.language}`} language={draft.language} code={draft.sourceCode} fontSize={fontSize}
          readOnly={disabled} onChange={(value) => { if (!disabled) edit(content.problemId, draft.language, value); }} />
      </div>
      <section className="results stack-sm">
        <p role="status">{saved ? "서버 저장 완료" : "서버 저장 대기"}</p>
        <p>진단 중에는 판정·힌트를 제공하지 않습니다. 오프라인에서 기한이 지나면 서버에 저장된 마지막 코드로 자동 제출됩니다.</p>
      </section>
    </>}
    <div className="editor-actions">
      <Button onClick={onSave} disabled={disabled}>지금 저장</Button>
      <Button className="primary" onClick={onSubmit} disabled={disabled || !draft || !languages.includes(draft.language)}>
        {buffer?.pending ? "같은 제출 재요청" : "문항 제출"}
      </Button>
    </div>
  </div>;
}

export function DiagnosisEditor({ disabled, onSubmit, onSave }: { disabled: boolean; onSubmit: () => void; onSave: () => void }) {
  const { detail, activeProblem } = useDiagnosis();
  const selected = detail.problems.find((problem) => problem.problemId === activeProblem);
  const content = useResource(() => diagnosisApi.getProblem(detail.diagnosisId, activeProblem), `${detail.diagnosisId}:${activeProblem}`);
  const runtimes = useResource(judgeApi.getLanguages, "diagnosis-languages");
  if (content.loading || runtimes.loading) return <Loading />;
  if (content.error) return <ErrorState message={content.error.message} retry={content.retry} />;
  if (runtimes.error) return <ErrorState message={runtimes.error.message} retry={runtimes.retry} />;
  if (!content.data || !selected) return <Empty title="진단 문항이 없습니다." />;
  if (content.data.problemId !== activeProblem || content.data.problemRevisionId !== selected.problemRevisionId)
    return <ErrorState message="진단에 고정된 문제 회차를 확인할 수 없습니다." retry={content.retry} />;
  return <div className="workspace">
    <ProblemDescription key={activeProblem} problem={toEditorProblem(content.data)} diagnosisId={detail.diagnosisId} />
    <DiagnosisBuffer content={content.data} runtimes={runtimes.data?.content ?? []} disabled={disabled} onSubmit={onSubmit} onSave={onSave} />
  </div>;
}
