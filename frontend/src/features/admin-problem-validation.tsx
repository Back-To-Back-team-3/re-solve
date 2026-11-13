"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button, ErrorState, Loading } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { actionMessage } from "@/services/problem-authoring";
import { useResource } from "@/services/hooks";
import { ProblemValidationProvider, useProblemValidation } from "@/stores/problem-validation";
import type { AdminProblemDetail } from "@/services/types";

function ValidationStatus({ id, onApplied }: { id: string; onApplied: () => void }) {
  const resource = useResource(() => adminProblemApi.getValidation(id), id);
  useEffect(() => {
    if (resource.data?.resultApplied) onApplied();
  }, [resource.data?.resultApplied, onApplied]);
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  const result = resource.data;
  return <section className="card stack-sm">
    <h3>검증 #{id}</h3>
    <Button type="button" onClick={resource.retry}>검증 상태 새로고침</Button>
    {result && <>
      <p>{result.language} · {result.status} · 결과 반영: {result.resultApplied ? "완료" : "대기"}</p>
      <p>사용 회차: {result.currentProblemRevisionId ?? "없음"} · 통과 언어: {result.supportedLanguages.join(", ") || "없음"}</p>
      <p>최대 실행 시간: {result.maxTimeMs ?? "—"}ms</p>
      {result.message && <pre>{result.message}</pre>}
      {result.tests.map((test) => <p key={test.testOrder}>테스트 {test.testOrder}: {test.verdict ?? "미확정"} · {test.timeMs ?? "—"}ms · {test.memoryKb ?? "—"}KiB</p>)}
    </>}
  </section>;
}

function ValidationForm({ detail, onApplied }: { detail: AdminProblemDetail; onApplied: () => void }) {
  const router = useRouter();
  const { language, selectLanguage, drafts, setCode, validationId, setValidationId, pending, setPending } = useProblemValidation();
  const sourceCode = drafts[language] ?? "";
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const lock = useRef(false);
  async function validate() {
    if (lock.current) return;
    setError("");
    if (!sourceCode.trim() || new TextEncoder().encode(sourceCode).length > 64 * 1024) {
      setError("공백이 아닌 64KiB 이하 코드를 입력해 주세요."); return;
    }
    const body = { language, sourceCode }; const json = JSON.stringify(body);
    lock.current = true; setBusy(true);
    try {
      const request = pending[language]?.json === json ? pending[language] : { body, json, key: crypto.randomUUID() };
      setPending(request);
      const result = await adminProblemApi.validate(detail.problemId, detail.problemRevisionId, request.body, request.key);
      setValidationId(result.validationId); router.refresh();
    } catch (failure) { setError(actionMessage(failure)); }
    finally { lock.current = false; setBusy(false); }
  }
  return <section className="stack">
    <div className="card stack-sm">
      <h2>회차별 정답 코드 검증</h2>
      <select aria-label="검증 언어" className="input" value={language} disabled={busy}
        onChange={(event) => {
          const next = event.target.value;
          if (next === "java" || next === "python3" || next === "cpp") {
            selectLanguage(next);
          }
        }}>
        <option value="java">Java</option><option value="python3">Python</option><option value="cpp">C++</option>
      </select>
      <label className="field">검증 코드<textarea aria-label="검증 코드" className="input mono" rows={10} value={sourceCode} disabled={busy}
        onChange={(event) => setCode(event.target.value)} /></label>
      <Button disabled={busy} onClick={validate}>{busy ? "접수 중…" : "검증 접수·재요청"}</Button>
      {error && <p className="error-text" role="alert">{error}</p>}
      <p>같은 코드의 재요청은 원래 키를 유지합니다. 통과했어도 결과 반영이 완료되기 전에는 사용 회차가 바뀌지 않을 수 있습니다.</p>
      {detail.languageResults.map((result) => <Button key={result.validationId} onClick={() => setValidationId(result.validationId)}>
        {result.language} 검증 #{result.validationId} · {result.status} · {result.resultApplied ? "반영 완료" : "반영 대기"}
      </Button>)}
    </div>
    {validationId && <ValidationStatus key={validationId} id={validationId} onApplied={onApplied} />}
  </section>;
}

export function AdminProblemValidation({ detail, onApplied }: { detail: AdminProblemDetail; onApplied: () => void }) {
  return <ProblemValidationProvider key={`${detail.problemId}:${detail.problemRevisionId}`} detail={detail}>
    <ValidationForm detail={detail} onApplied={onApplied} />
  </ProblemValidationProvider>;
}
