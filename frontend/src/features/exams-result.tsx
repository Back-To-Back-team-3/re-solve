"use client";
import { useState } from "react";
import { useResource } from "@/services/hooks";
import { contestApi } from "@/services/api";
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
import type { ResultStatus } from "@/services/types";

const VERDICT_LABELS: Record<string, string> = {
  AC: "맞았습니다",
  WA: "틀렸습니다",
  TLE: "시간 초과",
  CE: "컴파일 오류",
  MLE: "메모리 초과",
  RE: "런타임 오류",
};

const RESULT_STATUS_LABEL: Record<ResultStatus, string> = {
  HIDDEN: "결과 집계 중",
  PROVISIONAL: "잠정 결과",
  FINALIZED: "최종 결과",
};

const RESULT_STATUS_TONE: Record<ResultStatus, string> = {
  HIDDEN: "",
  PROVISIONAL: "accent",
  FINALIZED: "success",
};

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
  const rDetail = useResource(() => contestApi.getExamDetail(id), `detail-${id}`);
  const rResult = useResource(() => contestApi.getMyResult(id), `result-${id}`);

  if (rDetail.loading || rResult.loading)
    return (
      <div className="container">
        <Loading />
      </div>
    );
  if (rDetail.error)
    return (
      <div className="container">
        <ErrorState message={rDetail.error.message} retry={rDetail.retry} />
      </div>
    );
  if (rResult.error)
    return (
      <div className="container">
        <ErrorState message={rResult.error.message} retry={rResult.retry} />
      </div>
    );

  const exam = rDetail.data;
  if (!exam) return <NotFound what="시험" />;

  const myResult = rResult.data;
  const rows = (myResult?.problems ?? []).map((mp) => ({
    problemId: mp.problemId,
    title: problems.find((p) => p.id === mp.problemId)?.title ?? mp.problemId,
    verdict: mp.verdict,
    passedCount: mp.passedCount,
    totalCount: mp.totalCount,
  }));
  const correct = rows.filter((r) => r.verdict === "AC").length;
  const resultStatus = myResult?.resultStatus ?? "HIDDEN";

  return (
    <div className="stack">
      <Heading
        title="도전의 기록"
        eyebrow="CHALLENGE COMPLETE"
        description={exam.title}
        action={
          <Badge tone={RESULT_STATUS_TONE[resultStatus]}>
            {RESULT_STATUS_LABEL[resultStatus]}
          </Badge>
        }
      />
      <div className="card hero">
        <Pet level={correct ? 3 : 1} size={120} />
        <div className="grow">
          <h2>한 번의 도전이, 다음 성장의 시작!</h2>
          <p>
            {myResult
              ? "응시 기록을 확인하고 다시 연습해 보세요."
              : "아직 이 사용자의 응시 기록이 없습니다."}
          </p>
        </div>
        <div>
          <p className="score">
            {rows.length ? Math.round((correct / rows.length) * 100) : 0}
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
              <th>복습</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.problemId}>
                <td>{r.title}</td>
                <td>
                  <Badge tone={r.verdict === "AC" ? "success" : r.verdict ? "error" : ""}>
                    {r.verdict ? (VERDICT_LABELS[r.verdict] ?? r.verdict) : "미제출"}
                  </Badge>
                </td>
                <td>
                  {r.passedCount != null && r.totalCount != null
                    ? `${r.passedCount} / ${r.totalCount}`
                    : "—"}
                </td>
                <td>
                  <Go className="small" href={`/problems/${r.problemId}`}>
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
