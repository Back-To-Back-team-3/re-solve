"use client";
import { useState } from "react";
import { ArrowRight } from "lucide-react";
import { studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type {
  AssignmentItemStatus,
  AssignmentStatus,
  AssignmentSummary,
} from "@/services/types";
import {
  Badge,
  Empty,
  ErrorState,
  Go,
  Loading,
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
