"use client";
import { useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useResource } from "@/services/hooks";
import { judgeApi, problemApi } from "@/services/api";
import type { Language, Problem, ProblemDetail } from "@/services/types";
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
export function ProblemDescription({
  problem,
  exam = false,
}: {
  problem: Problem;
  exam?: boolean;
}) {
  const [tab, setTab] = useState("description"),
    r = useResource(() => judgeApi.getSubmissions(problem.id), problem.id + "-subs");
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
function toProblem(d: ProblemDetail): Problem {
  return {
    id: d.problemId,
    problemRevisionId: d.problemRevisionId,
    title: d.title,
    difficulty: d.difficulty,
    tags: d.tags,
    description: d.description,
    constraints: d.constraints,
    examples: d.examples,
    functionSpec: {
      name: "solution",
      parameters: d.functionSpec.parameters.map((p) => ({
        name: p.name,
        type: p.type.kind,
      })),
      returnType: d.functionSpec.returnType.kind,
    },
    starterCodes: Object.fromEntries(
      d.starterCodes.map((s) => [s.language, s.sourceCode]),
    ) as Record<Language, string>,
    samples: d.samples.map((s) => ({
      input: Object.values(s.argumentTexts).join(", "),
      expected: s.expectedReturnText,
    })),
  };
}

export function ProblemPage({ id }: { id: string }) {
  const member = useWorkspace((s) => s.member);
  const r = useResource(() => problemApi.getDetail(id), id);
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
  const problem = toProblem(r.data);
  return (
    <>
      <div className="exam-bar">
        <Go className="small" href="/problems">
          ← 문제 목록
        </Go>
      </div>
      <div className="workspace">
        <ProblemDescription key={id} problem={problem} />
        <CodeEditor
          problem={problem}
          userId={member?.memberId || "guest"}
        />
      </div>
    </>
  );
}
