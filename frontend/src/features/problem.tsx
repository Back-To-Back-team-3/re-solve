"use client";
import { useState } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import type { Problem } from "@/services/types";
import { useCatalog } from "@/services/catalog";
import {
  Badge,
  ErrorState,
  Go,
  Loading,
  NotFound,
  Tabs,
} from "@/components/ui";
import { CodeEditor } from "./editor";
import { SubmissionList } from "./problem-list";
import { Board, Note } from "./board";
export function ProblemDescription({
  problem,
  exam = false,
}: {
  problem: Problem;
  exam?: boolean;
}) {
  const { tags } = useCatalog();
  const [tab, setTab] = useState("description"),
    r = useResource(() => api.submissions(problem.id), problem.id);
  return (
    <section className="problem-panel stack">
      <div className="row wrap">
        <Badge>Lv. {problem.difficulty}</Badge>
        <Badge tone="accent">
          {tags.find((t) => t.id === problem.tagId)?.name}
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
          <ErrorState message={r.error} retry={r.retry} />
        ) : (
          <SubmissionList items={r.data || []} />
        ))}
      {tab === "questions" && (
        <Board scope={`problem:${problem.id}`} title="질문 작성" />
      )}
      {tab === "notes" && <Note problemId={problem.id} />}
    </section>
  );
}
export function ProblemPage({ id }: { id: string }) {
  const r = useResource(
    async () => ({
      problem: await api.problem(id),
      user: await api.currentUser(),
    }),
    id,
  );
  if (r.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (r.error)
    return (
      <div className="container">
        <ErrorState message={r.error} retry={r.retry} />
      </div>
    );
  if (!r.data?.problem) return <NotFound what="문제" />;
  return (
    <>
      <div className="exam-bar">
        <Go className="small" href="/problems">
          ← 문제 목록
        </Go>
        <span className="muted">자동 저장 · 모의 채점</span>
      </div>
      <div className="workspace">
        <ProblemDescription key={id} problem={r.data.problem} />
        <CodeEditor
          problem={r.data.problem}
          userId={r.data.user?.id || "guest"}
        />
      </div>
    </>
  );
}
