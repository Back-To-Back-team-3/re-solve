"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useResource } from "@/services/hooks";
import { diagnosisApi } from "@/services/api";
import { diagnosisStatusLabel } from "@/services/diagnosis";
import { SkillPage } from "@/features/account-skill";
import { Pager } from "@/features/problem-list";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { useWorkspace } from "@/stores/workspace";
import { DiagnosisProvider } from "@/stores/diagnosis";
import { DiagnosisRoom } from "@/features/diagnosis-room";

export function DiagnosisListPage() {
  const selectedTag = useSearchParams().get("tagId") ?? undefined;
  const [page, setPage] = useState(0);
  const history = useResource(() => diagnosisApi.getHistory(page), `diagnosis-history:${page}`);
  return <div className="container stack">
    <Heading title="알고리즘 성장 진단" description="태그별 3문항을 60분 동안 풀이합니다. 판정은 종료 후 공개됩니다." />
    <SkillPage selectedTag={selectedTag} />
    <section className="card stack-sm">
      <h2>나의 진단 이력</h2>
      {history.loading ? <Loading /> : history.error ? <ErrorState message={history.error.message} retry={history.retry} /> : <>
        {!history.data?.content.length && <p>아직 진단 이력이 없습니다.</p>}
        {history.data?.content.map((attempt) => <div key={attempt.diagnosisId} className="row between wrap">
          <span>진단 #{attempt.diagnosisId} · {diagnosisStatusLabel(attempt.status)}</span>
          <Go href={`/diagnoses/${attempt.diagnosisId}`}>{attempt.status === "IN_PROGRESS" ? "이어서 풀기" : "결과 조회"}</Go>
        </div>)}
        <Pager page={page} totalPages={history.data?.totalPages ?? 0} onChange={setPage} />
        <Button className="small" onClick={history.retry}>진단 이력 새로고침</Button>
      </>}
    </section>
  </div>;
}

export function DiagnosisPage({ id }: { id: string }) {
  const member = useWorkspace((state) => state.member);
  const resource = useResource(() => diagnosisApi.getDetail(id), `diagnosis:${member?.memberId}:${id}`);
  if (!member) return <Go href="/login">로그인 후 진단에 응시해 주세요.</Go>;
  if (resource.loading) return <Loading />;
  if (resource.error) return <div className="container"><ErrorState message={resource.error.message} retry={resource.retry} /></div>;
  if (!resource.data || resource.data.diagnosisId !== id) return <Loading />;
  return <DiagnosisProvider key={`${member.memberId}:${id}`} detail={resource.data}>
    <DiagnosisRoom userId={member.memberId} />
  </DiagnosisProvider>;
}
