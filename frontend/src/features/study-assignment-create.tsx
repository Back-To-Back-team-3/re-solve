"use client";
import { useRef, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { actionErrorMessage, problemTagApi, studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { ProblemListItem, StudyDetail } from "@/services/types";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { assignmentDifficultyLimit, canCreateAssignment, isAssignmentDifficultyAllowed } from "@/features/study-assignment-policy";
import { AssignmentProblemPicker } from "@/features/study-assignment-problem-picker";

export function StudyAssignmentCreate() {
  const { id } = useParams<{ id: string }>();
  const resource = useResource(() => studyApi.getDetail(id), id);
  return <div className="container stack">
    <Go href={`/studies/${id}`}>← 스터디로 돌아가기</Go>
    <Heading title="문제집 만들기" eyebrow="STUDY ASSIGNMENT" />
    {resource.loading ? <Loading /> : resource.error ? (
      <ErrorState message={resource.error.message} retry={resource.retry} />
    ) : resource.data && <AssignmentCreateForm key={id} initialStudy={resource.data} />}
  </div>;
}

function AssignmentCreateForm({ initialStudy }: { initialStudy: StudyDetail }) {
  const router = useRouter();
  const [study, setStudy] = useState(initialStudy);
  const [selected, setSelected] = useState<ProblemListItem[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const tags = useResource(() => problemTagApi.getTags(), "assignment-tags");
  const limit = assignmentDifficultyLimit(study.skillLevel);
  const allowed = canCreateAssignment(study);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || !allowed || limit === null) return;
    const values = new FormData(event.currentTarget);
    const startsAt = new Date(String(values.get("startsAt")));
    const deadlineAt = new Date(String(values.get("deadlineAt")));
    const title = String(values.get("title") ?? "").trim();
    if (!title) { setError("제목을 입력해 주세요."); return; }
    if (!Number.isFinite(startsAt.getTime()) || !Number.isFinite(deadlineAt.getTime()) || deadlineAt <= startsAt) {
      setError("마감 시각은 시작 시각보다 늦어야 합니다."); return;
    }
    if (selected.length < 1 || selected.length > 20 || new Set(selected.map((p) => p.problemId)).size !== selected.length) {
      setError("중복 없이 문제를 1~20개 선택해 주세요."); return;
    }
    inFlight.current = true;
    setBusy(true); setError("");
    let createdSuccessfully = false;
    try {
      // 작성 중 변경된 권한·수준을 확인한다. 이후 경합의 최종 검증은 생성 API가 담당한다.
      const latest = await studyApi.getDetail(study.studyId);
      setStudy(latest);
      if (!canCreateAssignment(latest)) {
        setError("종료되지 않은 스터디의 승인된 스터디장·운영진만 문제집을 만들 수 있습니다."); return;
      }
      const latestLimit = assignmentDifficultyLimit(latest.skillLevel);
      if (latestLimit === null || selected.some((p) => !isAssignmentDifficultyAllowed(p.difficulty, latestLimit))) {
        setError("스터디 수준을 확인하고 난이도 상한을 초과한 문제를 제거해 주세요."); return;
      }
      const created = await studyApi.createAssignment(study.studyId, {
        title, goal: String(values.get("goal") ?? "").trim(),
        tagId: String(values.get("tagId") ?? "") || null,
        problemIds: selected.map((problem) => problem.problemId),
        isPreSolvedAllowed: values.get("isPreSolvedAllowed") === "on",
        startsAt: startsAt.toISOString(), deadlineAt: deadlineAt.toISOString(),
      });
      createdSuccessfully = true;
      router.push(`/studies/${study.studyId}/assignments/${created.assignmentId}`);
      router.refresh();
    } catch (failure) {
      setError(actionErrorMessage(failure));
    } finally {
      if (!createdSuccessfully) { inFlight.current = false; setBusy(false); }
    }
  }

  return <form className="card stack" onSubmit={submit}>
    <p>{study.name} · {study.skillLevel}</p>
    {!allowed && <p role="alert">종료되지 않은 스터디의 승인된 스터디장·운영진만 문제집을 만들 수 있습니다.</p>}
    {limit === null && <p role="alert">스터디 실력 수준을 확인해 주세요. LV1~LV3 스터디에서 문제집을 만들 수 있습니다.</p>}
    {error && <p role="alert" className="error-text">{error}</p>}
    <fieldset className="stack" disabled={busy || !allowed || limit === null}>
      <label className="field">제목<input className="input" name="title" required maxLength={200} /></label>
      <label className="field">학습 목표<textarea className="input" name="goal" maxLength={500} rows={3} /></label>
      <label className="field">대표 태그
        <select className="input" name="tagId" defaultValue="">
          <option value="">선택 안 함</option>
          {tags.data?.content.map((tag) => <option key={tag.tagId} value={tag.tagId}>{tag.name}</option>)}
        </select>
      </label>
      {tags.error && <div role="alert"><p>{tags.error.message}</p><Button type="button" onClick={tags.retry}>태그 다시 시도</Button></div>}
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="field">시작 시각<input className="input" type="datetime-local" name="startsAt" required /></label>
        <label className="field">마감 시각<input className="input" type="datetime-local" name="deadlineAt" required /></label>
      </div>
      <p className="muted">현재 기기의 시간대를 기준으로 입력합니다. 시작 시각이 지나면 서버에서 순서대로 문제집을 엽니다.</p>
      <label className="row"><input type="checkbox" name="isPreSolvedAllowed" />기존 풀이 인정</label>
      {allowed && limit !== null && <AssignmentProblemPicker limit={limit} selected={selected} onChange={setSelected} />}
      <Button type="submit" className="primary" disabled={busy || selected.length === 0}>
        {busy ? "생성 중…" : "문제집 생성"}
      </Button>
    </fieldset>
  </form>;
}
