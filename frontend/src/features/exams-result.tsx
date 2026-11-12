"use client";
import { useState } from "react";
import { api, labels } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import {
  Badge,
  ErrorState,
  Go,
  Heading,
  Loading,
  NotFound,
} from "@/components/ui";
import { Pet } from "@/components/pet";
import { useCatalog } from "@/services/catalog";
import { ExamRanking } from "./exams-ranking";

export function ExamResult({ id }: { id: string }) {
  const [tab, setTab] = useState<"my" | "ranking">("my");
  return (
    <div className="container stack">
      <div className="tab-bar row">
        <button
          className={tab === "my" ? "active" : ""}
          onClick={() => setTab("my")}
        >
          내 성적
        </button>
        <button
          className={tab === "ranking" ? "active" : ""}
          onClick={() => setTab("ranking")}
        >
          전체 순위
        </button>
      </div>
      {tab === "my" ? <MyResultTab id={id} /> : <ExamRanking id={id} />}
    </div>
  );
}

function MyResultTab({ id }: { id: string }) {
  const { problems } = useCatalog();
  const r = useResource(
    async () => ({
      exam: await api.exam(id),
      session: await api.session(id),
      submissions: await api.submissions(undefined, id),
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
        <ErrorState message={r.error.message} retry={r.retry} />
      </div>
    );
  const e = r.data?.exam;
  if (!e) return <NotFound what="시험" />;
  const rows = e.problemIds.map((pid) => ({
      problem: problems.find((p) => p.id === pid)!,
      submission: r.data!.submissions.find(
        (s) => s.problemId === pid && s.kind === "submit",
      ),
    })),
    correct = rows.filter((x) => x.submission?.verdict === "AC").length;
  return (
    <div className="stack">
      <Heading
        title="도전의 기록"
        eyebrow="CHALLENGE COMPLETE"
        description={e.title}
        action={<Badge tone="warning">모의 결과</Badge>}
      />
      <div className="card hero">
        <Pet level={correct ? 3 : 1} size={120} />
        <div className="grow">
          <h2>한 번의 도전이, 다음 성장의 시작!</h2>
          <p>
            {r.data?.session
              ? "응시 기록을 확인하고 다시 연습해 보세요."
              : "아직 이 사용자의 응시 기록이 없습니다."}
          </p>
        </div>
        <div>
          <p className="score">
            {Math.round((correct / rows.length) * 100)}
            <small>/100</small>
          </p>
          <p>
            {correct} / {rows.length}문제 정답
          </p>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>문제</th>
              <th>결과</th>
              <th>통과 테스트</th>
              <th>실행 시간</th>
              <th>언어</th>
              <th>복습</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ problem: p, submission: s }) => (
              <tr key={p.id}>
                <td>{p.title}</td>
                <td>
                  <Badge
                    tone={s?.verdict === "AC" ? "success" : s ? "error" : ""}
                  >
                    {s ? labels[s.verdict || s.state] : "미제출"}
                  </Badge>
                </td>
                <td>{s ? `${s.passedCount} / ${s.totalCount}` : "—"}</td>
                <td>{s ? `${s.cpuTimeMs}ms` : "—"}</td>
                <td>{s ? labels[s.language] : "—"}</td>
                <td>
                  <Go className="small" href={`/problems/${p.id}`}>
                    다시 풀기
                  </Go>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="notice">
        실제 시험의 성적이나 정책이 아닙니다. 숨김 테스트의 입력·기대값·반환값은
        공개하지 않습니다.
      </p>
      <div className="row">
        <Go href="/exams">모의 코테 목록</Go>
        <Go className="primary" href="/dashboard">
          학습 현황 보기
        </Go>
      </div>
    </div>
  );
}
