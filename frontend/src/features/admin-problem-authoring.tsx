"use client";
import { useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { actionMessage, parseProblemContent, parseProblemFunction } from "@/services/problem-authoring";
import { writeProblemTests } from "@/services/problem-test-inputs";
import type { AdminProblemDetail, ProblemWriteRequest } from "@/services/types";
import { ProblemAuthoringProvider, useProblemAuthoring } from "@/stores/problem-authoring";
import { AdminMarkdown, AdminProblemImages } from "@/features/admin-problem-images";
import { AdminProblemTestInputs } from "@/features/admin-problem-test-inputs";
import { AdminProblemFunction } from "@/features/admin-problem-function";

function ProblemForm({ initial }: { initial: AdminProblemDetail | null }) {
  const router = useRouter();
  const { scope, setScope, functionSpec, setFunctionSpec, fields, setFields, tests, setTests, rowVersion, setRowVersion, conflict, setConflict, latest, setLatest } = useProblemAuthoring();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const description = useRef<HTMLTextAreaElement>(null);
  const pending = useRef<{ body: ProblemWriteRequest; json: string; key: string } | null>(null);
  const lock = useRef(false);
  /** 첨부 대기 중 편집한 본문도 유지하며 현재 선택 범위에 이미지 참조를 삽입한다. */
  function insertImage(markdown: string) {
    const editor = description.current;
    const text = editor?.value ?? fields.description;
    const start = editor?.selectionStart ?? text.length;
    const end = editor?.selectionEnd ?? start;
    const next = text.slice(0, start) + markdown + text.slice(end);
    setFields((current) => ({ ...current, description: next }));
    requestAnimationFrame(() => { editor?.focus(); editor?.setSelectionRange(start + markdown.length, start + markdown.length); });
  }
  async function save(event: React.FormEvent) {
    event.preventDefault(); if (lock.current || uploading) return;
    setError("");
    try {
      const content = parseProblemContent({ ...fields,
        functionSpec: JSON.stringify(functionSpec),
        testCases: JSON.stringify(writeProblemTests(parseProblemFunction(JSON.stringify(functionSpec)), tests)) });
      const body = initial ? { ...content, rowVersion } : { ...content, scope };
      const json = JSON.stringify(body);
      if (new TextEncoder().encode(json).length > 8 * 1024 * 1024) throw new Error("등록 본문은 8MiB 이하여야 합니다.");
      const request = pending.current?.json === json ? pending.current : { body, json, key: crypto.randomUUID() };
      pending.current = request; lock.current = true; setBusy(true);
      const result = await adminProblemApi.save(initial?.problemId ?? null, request.body, request.key);
      router.push(`/admin/problems/${initial?.problemId ?? result.problemId}`); router.refresh();
    } catch (failure) {
      setError(actionMessage(failure));
      if (typeof failure === "object" && failure !== null && "code" in failure && failure.code === "PROBLEM_VERSION_CONFLICT") setConflict(true);
    }
    finally { lock.current = false; setBusy(false); }
  }
  async function loadLatest() {
    if (!initial || lock.current) return;
    lock.current = true; setBusy(true);
    try { setLatest(await adminProblemApi.getDetail(initial.problemId)); }
    catch (failure) { setError(actionMessage(failure)); }
    finally { lock.current = false; setBusy(false); }
  }
  return <form className="stack" onSubmit={save}>
    {initial && <p>작성 회차 #{initial.problemRevisionId} · 사용 회차 #{initial.currentProblemRevisionId ?? "없음"} · 변경 버전 {rowVersion}</p>}
    <fieldset className="card stack-sm" disabled={busy}>
      {!initial && <label className="field">용도<select className="input" value={scope} onChange={(event) => setScope(event.target.value === "EXAM_ONLY" ? "EXAM_ONLY" : "GENERAL")}>
        <option value="GENERAL">일반 문제</option><option value="EXAM_ONLY">대회 전용</option>
      </select></label>}
      <label className="field">제목<input className="input" required maxLength={200} value={fields.title}
        onChange={(event) => setFields({ ...fields, title: event.target.value })} /></label>
      <label className="field">난이도<select className="input" value={fields.difficulty}
        onChange={(event) => setFields({ ...fields, difficulty: Number(event.target.value) })}>
        {[1, 2, 3].map((value) => <option key={value} value={value}>{value}</option>)}
      </select></label>
      <label className="field">태그 ID (쉼표 구분)<input className="input" value={fields.tagIds}
        onChange={(event) => setFields({ ...fields, tagIds: event.target.value })} /></label>
      <section className="stack-sm" aria-label="본문 편집">
        <label className="field" htmlFor="problem-description">문제 설명</label>
        <AdminProblemImages text={fields.description} insert={insertImage} onBusyChange={setUploading} />
        <textarea id="problem-description" ref={description} aria-label="문제 설명" className="input mono" rows={10} value={fields.description}
          onChange={(event) => setFields({ ...fields, description: event.target.value })} />
      </section>
      {([['constraints', '제한 사항'], ['examples', '입출력 예시'],
        ['executionLimits', '실행 제한 (JSON 또는 null)']] as const).map(([key, label]) =>
        <label className="field" key={key}>{label}<textarea aria-label={label} className="input mono" rows={6} value={fields[key]}
          onChange={(event) => setFields({ ...fields, [key]: event.target.value })} /></label>)}
      <AdminProblemFunction value={functionSpec} onChange={setFunctionSpec} />
      <AdminProblemTestInputs functionSpec={JSON.stringify(functionSpec)} value={tests} onChange={setTests} />
      <p>기존 테스트의 파일 ID는 요청에 포함하지 않습니다. 같은 내용의 재요청은 기존 키를 유지하고, 내용을 변경하면 새 요청으로 접수합니다.</p>
      <Button type="submit" disabled={busy || uploading}>{busy ? "저장 중…" : initial ? "새 회차 저장·재요청" : "문제 등록·재요청"}</Button>
    </fieldset>
    {error && <p className="error-text" role="alert">{error}</p>}
    {conflict && <section className="card stack-sm" aria-label="버전 충돌 복구">
      <p>작성한 입력을 유지하고 있습니다. 최신 서버 내용을 확인한 뒤 계속 편집하세요.</p>
      <Button type="button" disabled={busy} onClick={loadLatest}>최신 서버 내용 확인</Button>
      {latest && <>
        <p>최신 작성 회차 #{latest.problemRevisionId} · 변경 버전 {latest.rowVersion}</p>
        <details><summary>최신 서버 내용</summary><pre>{JSON.stringify(latest, null, 2)}</pre></details>
        <Button type="button" disabled={busy} onClick={() => { setRowVersion(latest.rowVersion); pending.current = null;
          setConflict(false); setLatest(null); setError(""); }}>현재 입력 유지·최신 버전으로 계속 편집</Button>
      </>}
    </section>}
    <details className="card"><summary>Markdown 미리보기</summary><AdminMarkdown text={`${fields.description}\n\n## 제한 사항\n${fields.constraints}\n\n## 예시\n${fields.examples}`} /></details>
  </form>;
}

export function AdminProblemAuthoringPage() {
  const { id } = useParams<{ id?: string }>();
  const resource = useResource(() => id ? adminProblemApi.getDetail(id) : Promise.resolve(null), id ?? "new");
  if (resource.loading && !resource.data) return <Loading />;
  if (resource.error && !resource.data) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  return <div className="container stack">
    <Heading title={id ? "새 문제 회차 등록" : "문제 등록"} action={<Go href="/admin/problems">문제 관리</Go>} />
    {resource.error && <ErrorState message={resource.error.message} retry={resource.retry} />}
    <ProblemAuthoringProvider key={id ?? "new"} initial={resource.data ?? null}><ProblemForm initial={resource.data ?? null} /></ProblemAuthoringProvider>
  </div>;
}
