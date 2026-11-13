"use client";
import { useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { actionMessage, parseProblemContent, parseProblemFunction } from "@/services/problem-authoring";
import { readProblemTests, writeProblemTests } from "@/services/problem-test-inputs";
import type { AdminProblemDetail, ProblemWriteRequest } from "@/services/types";
import { AdminMarkdown, AdminProblemImages } from "@/features/admin-problem-images";
import { AdminProblemTestInputs } from "@/features/admin-problem-test-inputs";

function ProblemForm({ initial }: { initial: AdminProblemDetail | null }) {
  const router = useRouter();
  const [scope, setScope] = useState<"GENERAL" | "EXAM_ONLY">("GENERAL");
  const [fields, setFields] = useState({
    title: initial?.title ?? "", description: initial?.description ?? "", constraints: initial?.constraints ?? "",
    examples: initial?.examples ?? "", difficulty: initial?.difficulty ?? 1, tagIds: initial?.tagIds.join(",") ?? "",
    functionSpec: JSON.stringify(initial?.functionSpec ?? { name: "solution", parameters: [{ name: "a", type: { kind: "INT32" } }], returnType: { kind: "INT32" } }, null, 2),
    executionLimits: JSON.stringify(initial?.executionLimits ?? null, null, 2),
  });
  const [tests, setTests] = useState(() => readProblemTests(parseProblemFunction(fields.functionSpec), initial?.testCases
    ?? [{ isSample: true, argumentTexts: { a: "1" }, expectedReturnText: "1" }, { isSample: false, argumentTexts: { a: "2" }, expectedReturnText: "2" }]));
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ body: ProblemWriteRequest; json: string; key: string } | null>(null);
  const lock = useRef(false);
  async function save(event: React.FormEvent) {
    event.preventDefault(); if (lock.current) return;
    setError("");
    try {
      const content = parseProblemContent({ ...fields,
        testCases: JSON.stringify(writeProblemTests(parseProblemFunction(fields.functionSpec), tests)) });
      const body = initial ? { ...content, rowVersion: initial.rowVersion } : { ...content, scope };
      const json = JSON.stringify(body);
      if (new TextEncoder().encode(json).length > 8 * 1024 * 1024) throw new Error("등록 본문은 8MiB 이하여야 합니다.");
      const request = pending.current?.json === json ? pending.current : { body, json, key: crypto.randomUUID() };
      pending.current = request; lock.current = true; setBusy(true);
      const result = await adminProblemApi.save(initial?.problemId ?? null, request.body, request.key);
      router.push(`/admin/problems/${initial?.problemId ?? result.problemId}`); router.refresh();
    } catch (failure) { setError(actionMessage(failure)); }
    finally { lock.current = false; setBusy(false); }
  }
  return <form className="stack" onSubmit={save}>
    {initial && <p>작성 회차 #{initial.problemRevisionId} · 사용 회차 #{initial.currentProblemRevisionId ?? "없음"} · 변경 버전 {initial.rowVersion}</p>}
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
      {([['description', '문제 설명'], ['constraints', '제한 사항'], ['examples', '입출력 예시'],
        ['functionSpec', '함수 명세 (JSON)'], ['executionLimits', '실행 제한 (JSON 또는 null)']] as const).map(([key, label]) =>
        <label className="field" key={key}>{label}<textarea aria-label={label} className="input mono" rows={6} value={fields[key]}
          onChange={(event) => setFields({ ...fields, [key]: event.target.value })} /></label>)}
      <AdminProblemTestInputs functionSpec={fields.functionSpec} value={tests} onChange={setTests} />
      <p>기존 테스트의 파일 ID는 요청에 포함하지 않습니다. 같은 내용의 재요청은 기존 키를 유지하고, 내용을 변경하면 새 요청으로 접수합니다.</p>
      <Button type="submit" disabled={busy}>{busy ? "저장 중…" : initial ? "새 회차 저장·재요청" : "문제 등록·재요청"}</Button>
    </fieldset>
    {error && <p className="error-text" role="alert">{error}</p>}
    {!busy && <AdminProblemImages insert={(markdown) => setFields((current) => ({ ...current, description: current.description + markdown }))} />}
    <details className="card"><summary>Markdown 미리보기</summary><AdminMarkdown text={`${fields.description}\n\n## 제한 사항\n${fields.constraints}\n\n## 예시\n${fields.examples}`} /></details>
  </form>;
}

export function AdminProblemAuthoringPage() {
  const { id } = useParams<{ id?: string }>();
  const resource = useResource(() => id ? adminProblemApi.getDetail(id) : Promise.resolve(null), id ?? "new");
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  return <div className="container stack">
    <Heading title={id ? "새 문제 회차 등록" : "문제 등록"} action={<Go href="/admin/problems">문제 관리</Go>} />
    <ProblemForm key={`${id}:${resource.data?.rowVersion}`} initial={resource.data ?? null} />
  </div>;
}
