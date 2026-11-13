"use client";
import { useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useResource } from "@/services/hooks";
import { judgeApi, problemApi } from "@/services/api";
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
import { ProblemSubmissionList } from "./problem-list";
import { Board, Note } from "./board";
import { useWorkspace } from "@/stores/workspace";
import { availableProblemLanguages, toEditorProblem } from "@/services/problem-editor";
export function ProblemDescription({
  problem,
  exam = false,
}: {
  problem: Problem;
  exam?: boolean;
}) {
  const [tab, setTab] = useState("description");
  const submissions = useWorkspace((state) => state.practiceSubmissions);
  const submissionVersion = JSON.stringify(Object.values(submissions).flatMap((attempt) =>
    attempt?.body.problemId === problem.id && attempt.submissionId
      ? [[attempt.submissionId, attempt.result?.status]] : [],
  ));
  const r = useResource(() => judgeApi.getSubmissions(problem.id), `${problem.id}-subs-${submissionVersion}`);
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
          <Markdown
            remarkPlugins={[remarkGfm]}
            skipHtml
          >{`${problem.description}\n\n## 제한 사항\n${problem.constraints}\n\n## 함수 명세\n\`solution(${problem.functionSpec.parameters.map((p) => `${p.name}: ${p.type}`).join(", ")}) → ${problem.functionSpec.returnType}\`\n\n## 입출력 예\n${problem.examples}`}</Markdown>
        </div>
      )}
      {tab === "submissions" &&
        (r.error ? (
          <ErrorState message={r.error.message} retry={r.retry} />
        ) : (
          <ProblemSubmissionList items={r.data?.content || []} />
        ))}
      {tab === "questions" && (
        <Board scope={`problem:${problem.id}`} title="질문 작성" />
      )}
      {tab === "notes" && <Note problemId={problem.id} />}
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
