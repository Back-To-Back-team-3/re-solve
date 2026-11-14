"use client";
import { useEffect, useMemo, useState } from "react";
import Image from "next/image";
import Markdown, { defaultUrlTransform } from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { useResource } from "@/services/hooks";
import { contestApi, judgeApi, problemApi } from "@/services/api";
import type { Problem } from "@/services/types";
import {
  Badge,
  ErrorState,
  Go,
  Loading,
  NotFound,
  Tabs,
} from "@/components/ui";
import { CodeEditor } from "./editor";
import { ProblemSubmissionList, Pager } from "@/features/problem-list";
import { Board, Note } from "./board";
import { useWorkspace } from "@/stores/workspace";
import { availableProblemLanguages, toEditorProblem } from "@/services/problem-editor";
/** 고정 회차·시험 문맥의 권한 검증을 거쳐 이미지 바이너리를 조회한다. (API 명세서 §2.1.3·§4.2.12) */
function ProblemBodyImage({ problemId, revisionId, imageId, alt, examId }: {
  problemId: string; revisionId: string; imageId: string; alt: string; examId?: string;
}) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    let objectUrl = "";
    const image = examId ? contestApi.getExamImage(examId, problemId, imageId)
      : problemApi.getImage(problemId, revisionId, imageId);
    image.then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl);
    }).catch((failure: unknown) => {
      if (active) setError(typeof failure === "object" && failure !== null && "message" in failure
        && typeof failure.message === "string" ? failure.message : "이미지를 불러오지 못했습니다.");
    });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [problemId, revisionId, imageId, examId]);
  if (error) return <span className="error-text" role="alert">{alt}: {error}</span>;
  return url ? <Image unoptimized src={url} alt={alt} width={800} height={400} className="h-auto w-auto max-w-full" />
    : <span>이미지 조회 중…</span>;
}

function ProblemMarkdown({ text, problem, examId }: { text: string; problem: Problem; examId?: string }) {
  const problemId = problem.id;
  const revisionId = problem.problemRevisionId;
  // 시험 타이머 갱신마다 이미지 컴포넌트를 재생성해 인증 요청을 반복하지 않는다.
  const components = useMemo<Components>(() => ({ img: ({ src, alt }) => {
    const match = typeof src === "string" ? /^image:([1-9][0-9]*)$/.exec(src) : null;
    if (match) return <ProblemBodyImage key={`${problemId}:${revisionId}:${examId}:${match[1]}`}
      problemId={problemId} revisionId={revisionId} imageId={match[1]} alt={alt ?? "문제 이미지"} examId={examId} />;
    return typeof src === "string" && src ? <Image unoptimized src={src} alt={alt ?? "문제 이미지"}
      width={800} height={400} className="h-auto w-auto max-w-full" /> : <span>이미지 주소를 확인해 주세요.</span>;
  } }), [problemId, revisionId, examId]);
  return <Markdown remarkPlugins={[remarkGfm]} skipHtml
    urlTransform={(url) => /^image:[1-9][0-9]*$/.test(url) ? url : defaultUrlTransform(url)}
    components={components}>{text}</Markdown>;
}

export function ProblemDescription({
  problem,
  exam = false,
  examId,
}: {
  problem: Problem;
  exam?: boolean;
  examId?: string;
}) {
  const [tab, setTab] = useState("description");
  const [page, setPage] = useState(0);
  const parameterNames = problem.functionSpec.parameters.map((parameter) => parameter.name);
  const structuredSamples = problem.samples.every((sample) => sample.argumentTexts !== undefined);
  const submissions = useWorkspace((state) => state.practiceSubmissions);
  const submissionVersion = JSON.stringify(Object.values(submissions).flatMap((attempt) =>
    attempt?.problemId === problem.id && attempt.submissionId
      ? [[attempt.submissionId, attempt.result?.status]] : [],
  ));
  const r = useResource(() => judgeApi.getSubmissions(problem.id, page), `${problem.id}-subs-${page}-${submissionVersion}`);
  return (
    <section className="problem-panel stack">
      <div className="row wrap">
        <Badge>Lv. {problem.difficulty}</Badge>
        <Badge tone="accent">
          {problem.tags[0]?.name}
        </Badge>
        <small>#{problem.id}</small>
      </div>
      <h1>{problem.title}</h1>
      {!exam && (
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { id: "description", label: "문제 설명" },
            { id: "submissions", label: "제출 내역" },
            { id: "questions", label: "질문" },
            { id: "notes", label: "풀이 노트" },
          ]}
        />
      )}
      {tab === "description" && (
        <div className="markdown">
          <ProblemMarkdown problem={problem} examId={examId} text={`${problem.description}\n\n## 제한 사항\n${problem.constraints}`} />
          <h2>입출력 예</h2>
          {problem.samples.length > 0 ? (
            <div className="table-wrap">
              <table className="my-0!" aria-label="입출력 예">
                <thead><tr>
                  {(structuredSamples ? parameterNames : ["입력"]).map((name) => <th scope="col" key={name}>{name}</th>)}
                  <th scope="col">result</th>
                </tr></thead>
                <tbody>{problem.samples.map((sample, index) => (
                  <tr key={index}>
                    {structuredSamples ? parameterNames.map((name) => (
                      <td key={name}><code>{sample.argumentTexts?.[name] ?? "—"}</code></td>
                    )) : <td><code>{sample.input}</code></td>}
                    <td><code>{sample.expected}</code></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          ) : null}
          <ProblemMarkdown problem={problem} examId={examId} text={problem.examples} />
        </div>
      )}
      {tab === "submissions" &&
        (r.error ? (
          <ErrorState message={r.error.message} retry={r.retry} />
        ) : (
          <>
            <ProblemSubmissionList items={r.data?.content || []} />
            <Pager page={page} totalPages={r.data?.totalPages ?? 0} onChange={setPage} />
          </>
        ))}
      {tab === "questions" && (
        <Board />
      )}
      {tab === "notes" && <Note />}
    </section>
  );
}
export function ProblemPage({ id }: { id: string }) {
  const member = useWorkspace((s) => s.member);
  const r = useResource(() => problemApi.getDetail(id), id);
  const runtimes = useResource(judgeApi.getLanguages, "judge-languages");
  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error.message} retry={r.retry} />
      </div>
    );
  if (!r.data) return <NotFound what="문제" />;
  if (r.data.problemId !== id) return <Loading />;
  const problem = toEditorProblem(r.data);
  return (
    <>
      <div className="exam-bar">
        <Go className="small" href="/problems">
          ← 문제 목록
        </Go>
      </div>
      <div className="workspace">
        <ProblemDescription key={id} problem={problem} />
        {runtimes.loading ? <Loading /> : runtimes.error ? (
          <ErrorState message={runtimes.error.message} retry={runtimes.retry} />
        ) : (
          <CodeEditor
            problem={problem}
            userId={member?.memberId || "guest"}
            availableLanguages={availableProblemLanguages(r.data, runtimes.data?.content ?? [])}
          />
        )}
      </div>
    </>
  );
}
