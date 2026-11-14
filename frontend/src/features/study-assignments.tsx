"use client";
import { useState } from "react";
import { ArrowRight } from "lucide-react";
import { studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type {
  AssignmentItemStatus,
  AssignmentStatus,
  AssignmentSummary,
  CompletionType,
} from "@/services/types";
import {
  Badge,
  Empty,
  ErrorState,
  Go,
  Heading,
  Loading,
  Section,
  Tabs,
} from "@/components/ui";
import { Pager } from "./problem-list";

const itemStatusTone: Record<AssignmentItemStatus, string> = {
  NOT_STARTED: "",
  IN_PROGRESS: "accent",
  COMPLETED: "success",
  INCOMPLETE: "error",
};

const itemStatusLabel: Record<AssignmentItemStatus, string> = {
  NOT_STARTED: "미시작",
  IN_PROGRESS: "진행 중",
  COMPLETED: "완료",
  INCOMPLETE: "미완료",
};

const emptyTitle: Record<AssignmentStatus, string> = {
  OPEN: "진행 중인 문제집이 없어요",
  SCHEDULED: "예정된 문제집이 없어요",
  CLOSED: "종료된 문제집이 없어요",
};

function AssignmentCard({
  studyId,
  a,
}: {
  studyId: string;
  a: AssignmentSummary;
}) {
  const dateRange = `${a.startsAt.slice(0, 10)} ~ ${a.deadlineAt ? a.deadlineAt.slice(0, 10) : "마감 없음"}`;

  return (
    <article className="card stack">
      <div className="row between">
        <h3>{a.title}</h3>
        {a.myStatus && (
          <Badge tone={itemStatusTone[a.myStatus.status]}>
            {itemStatusLabel[a.myStatus.status]}
          </Badge>
        )}
      </div>
      <p className="muted">{dateRange}</p>
      <p>{a.activeProblemCount}문제</p>
      {a.myStatus && a.activeProblemCount > 0 && (
        <div className="progress">
          {Array.from({ length: a.activeProblemCount }).map((_, i) => (
            <span
              key={i}
              className={i < a.myStatus!.completedCount ? "filled" : ""}
            />
          ))}
        </div>
      )}
      <Go className="actions" href={`/studies/${studyId}/assignments/${a.assignmentId}`}>
        자세히 보기
        <ArrowRight size={16} />
      </Go>
    </article>
  );
}

const assignmentStatusTone: Record<AssignmentStatus, string> = {
  OPEN: "success",
  SCHEDULED: "accent",
  CLOSED: "",
};

const assignmentStatusLabel: Record<AssignmentStatus, string> = {
  OPEN: "진행 중",
  SCHEDULED: "예정",
  CLOSED: "종료",
};

const completionTone: Record<CompletionType, string> = {
  PRE_SOLVED: "accent",
  ON_TIME: "success",
  LATE: "pending",
};

const completionLabel: Record<CompletionType, string> = {
  PRE_SOLVED: "기존 풀이",
  ON_TIME: "기한 내",
  LATE: "지각",
};

export function AssignmentDetail({
  studyId,
  assignmentId,
}: {
  studyId: string;
  assignmentId: string;
}) {
  const r = useResource(
    () => studyApi.getAssignmentDetail(studyId, assignmentId),
    `${studyId}:${assignmentId}`,
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

  const a = r.data!;
  const dateRange = `${a.startsAt.slice(0, 10)} ~ ${a.deadlineAt ? a.deadlineAt.slice(0, 10) : "마감 없음"}`;

  return (
    <div className="container stack">
      <Go className="small" href={`/studies/${studyId}`}>
        ← 스터디로 돌아가기
      </Go>
      <Heading
        title={a.title}
        eyebrow="ASSIGNMENT"
        description={`${a.goal} · ${dateRange}`}
        action={
          <Badge tone={assignmentStatusTone[a.status]}>
            {assignmentStatusLabel[a.status]}
          </Badge>
        }
      />

      <div className="row">
        <section className="card grow stack-sm">
          <p className="muted">스터디 평균 완료율</p>
          <p>
            <b>{Math.round(a.studyStats.avgCompletionRate * 100)}%</b>
          </p>
        </section>
        <section className="card grow stack-sm">
          <p className="muted">기한 내 완료율</p>
          <p>
            <b>{Math.round(a.studyStats.avgOnTimeRate * 100)}%</b>
          </p>
        </section>
      </div>

      <Section title="문제 목록">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>제목</th>
                <th>난이도</th>
                <th>완료 유형</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {a.problems.map((p) => (
                <tr key={p.problemId} className={p.status === "EXCLUDED" ? "opacity-50" : ""}>
                  <td>
                    <span className={p.status === "EXCLUDED" ? "line-through" : ""}>
                      <Go href={`/problems/${p.problemId}`}>{p.title}</Go>
                    </span>
                  </td>
                  <td>
                    <Badge>Lv. {p.difficulty}</Badge>
                  </td>
                  <td>
                    {p.myCompletionType && (
                      <Badge tone={completionTone[p.myCompletionType]}>
                        {completionLabel[p.myCompletionType]}
                      </Badge>
                    )}
                  </td>
                  <td>
                    {p.status === "EXCLUDED" && <Badge>제외됨</Badge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      <Section title="구성원 진행 현황">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>닉네임</th>
                <th>상태</th>
                <th>완료</th>
                <th>기한 내</th>
                <th>전체</th>
              </tr>
            </thead>
            <tbody>
              {a.memberProgress.map((m) => (
                <tr key={m.memberId}>
                  <td>{m.nickname}</td>
                  <td>
                    <Badge tone={itemStatusTone[m.status]}>
                      {itemStatusLabel[m.status]}
                    </Badge>
                  </td>
                  <td>{m.completedCount}</td>
                  <td>{m.onTimeCount}</td>
                  <td>{m.activeProblemCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </div>
  );
}

export function AssignmentsTab({
  studyId,
  version,
}: {
  studyId: string;
  version: number;
}) {
  const [assignTab, setAssignTab] = useState<AssignmentStatus>("OPEN");
  const [page, setPage] = useState(0);

  const assignments = useResource(
    () => studyApi.getAssignments(studyId, { status: assignTab, page, size: 10 }),
    `${studyId}:asgn:${assignTab}:${page}:${version}`,
  );

  function handleTabChange(next: string) {
    setAssignTab(next as AssignmentStatus);
    setPage(0);
  }

  return (
    <div className="stack">
      <Tabs
        value={assignTab}
        onChange={handleTabChange}
        items={[
          { id: "OPEN", label: "진행 중" },
          { id: "SCHEDULED", label: "예정" },
          { id: "CLOSED", label: "종료" },
        ]}
      />
      {assignments.loading && <Loading />}
      {assignments.error && (
        <ErrorState message={assignments.error.message} retry={assignments.retry} />
      )}
      {!assignments.loading && !assignments.error && assignments.data && (
        <>
          {assignments.data.content.length === 0 ? (
            <Empty title={emptyTitle[assignTab]} description="스터디 운영 화면에서 문제집을 만들 수 있습니다." />
          ) : (
            assignments.data.content.map((a) => (
              <AssignmentCard key={a.assignmentId} studyId={studyId} a={a} />
            ))
          )}
          <Pager
            page={assignments.data.page}
            totalPages={assignments.data.totalPages}
            onChange={setPage}
          />
        </>
      )}
    </div>
  );
}
