"use client";
import { useCallback } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { useWorkspace } from "@/stores/workspace";
import { useResource } from "@/services/hooks";
import { Pager } from "@/features/problem-list";
import { AdminMarkdown } from "@/features/admin-problem-images";
import { AdminProblemValidation } from "@/features/admin-problem-validation";

function ProblemView({ id, revision }: { id: string; revision?: string }) {
  const pageKey = JSON.stringify(["admin-problem-history", id]);
  const page = useWorkspace((state) => state.viewPages[pageKey] ?? 0);
  const selectPage = useWorkspace((state) => state.setViewPage);
  const setPage = (value: number) => selectPage(pageKey, value);
  const resource = useResource(() => adminProblemApi.getDetail(id, revision), `${id}:${revision}`);
  const history = useResource(() => adminProblemApi.getRevisions(id, page), `${id}:${page}`);
  const { retry: refreshDetail } = resource;
  const { retry: refreshHistory } = history;
  const refreshApplied = useCallback(() => {
    refreshDetail();
    refreshHistory();
  }, [refreshDetail, refreshHistory]);
  if (resource.loading && !resource.data) return <Loading />;
  if (resource.error && !resource.data) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  if (!resource.data) return null;
  const detail = resource.data;
  return <div className="container stack">
    <Heading title={detail.title} action={<Go href="/admin/problems">공개·상태 관리</Go>} />
    {resource.error && <ErrorState message={resource.error.message} retry={resource.retry} />}
    <div className="row wrap">
      <Go href={`/admin/problems/${id}/edit`}>최신 회차에서 수정</Go>
      <Button onClick={resource.retry}>상세 새로고침</Button>
    </div>
    <section className="card stack-sm">
      <p>조회 작성 회차: {detail.problemRevisionId} ({detail.revisionNumber}회차)</p>
      <p>현재 사용 회차: {detail.currentProblemRevisionId ?? "없음"}</p>
      <p>작성자: {detail.authorId} · 변경 버전: {detail.rowVersion} · {detail.scope} · {detail.visibility}</p>
      <p>지원 언어: {detail.supportedLanguages.join(", ") || "없음"}</p>
      <AdminMarkdown text={`${detail.description}\n\n## 제한 사항\n${detail.constraints}\n\n## 예시\n${detail.examples}`} />
      <details><summary>함수 명세·실행 제한·테스트</summary>
        <pre>{JSON.stringify({ functionSpec: detail.functionSpec, executionLimits: detail.executionLimits, testCases: detail.testCases }, null, 2)}</pre>
      </details>
    </section>
    <section className="card stack-sm">
      <h2>수정 회차 이력</h2>
      {history.loading ? <Loading /> : history.error ? <ErrorState message={history.error.message} retry={history.retry} /> : <>
        {history.data?.content.map((item) => <Go key={item.problemRevisionId} href={`/admin/problems/${id}?revisionId=${item.problemRevisionId}`}>
          {item.revisionNumber}회차 · {item.title} · 통과 언어 {item.supportedLanguages.join(", ") || "없음"}
        </Go>)}
        <Pager page={page} totalPages={history.data?.totalPages ?? 0} onChange={setPage} />
      </>}
    </section>
    <AdminProblemValidation detail={detail} onApplied={refreshApplied} />
  </div>;
}

export function AdminProblemDetailPage() {
  const { id } = useParams<{ id: string }>();
  const revision = useSearchParams().get("revisionId") ?? undefined;
  return <ProblemView key={`${id}:${revision}`} id={id} revision={revision} />;
}
