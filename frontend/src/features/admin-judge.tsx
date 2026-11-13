"use client";
import { useState, type FormEvent } from "react";
import { Button, Empty, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { Pager } from "@/features/problem-list";
import { adminJudgeApi } from "@/services/api";
import { useWorkspace } from "@/stores/workspace";
import { useResource } from "@/services/hooks";
import type { JudgeJobFilter } from "@/services/types";

function JobList({ filter }: { filter: JudgeJobFilter }) {
  const page = useWorkspace((state) => state.viewPages["admin-judge-jobs"] ?? 0);
  const selectPage = useWorkspace((state) => state.setViewPage);
  const setPage = (value: number) => selectPage("admin-judge-jobs", value);
  const resource = useResource(() => adminJudgeApi.getList(filter, page), String(page));
  if (resource.loading) return <Loading />;
  if (resource.error) return <ErrorState message={resource.error.message} retry={resource.retry} />;
  const data = resource.data;
  return <section className="stack">
    <Button onClick={resource.retry}>목록 새로고침</Button>
    {!data?.content.length ? <Empty title="채점 작업이 없어요" description="필터를 바꿔 보세요." /> : <div className="table-wrap"><table>
      <thead><tr>{["작업 / 제출", "상태 / 시도", "회원 / 문맥", "문제 회차", "실패 코드", "기한 / 갱신 시각"].map((title) => <th key={title}>{title}</th>)}</tr></thead>
      <tbody>{data.content.map((job) => <tr key={job.judgeJobId}>
        <td><Go href={`/admin/judge-jobs/${job.judgeJobId}`}>작업 {job.judgeJobId}</Go><p>제출 {job.submissionId}</p></td>
        <td>{job.status}<p>채점 {job.judgeAttempt}회차 · 실행 {job.tryCount}회</p></td>
        <td>회원 {job.memberId}<p>{job.context} · {job.contextId ?? "없음"}</p></td>
        <td>{job.problemRevisionId}</td><td>{job.failureCode ?? "없음"}</td>
        <td>{job.deadlineAt}<p>{job.updatedAt}</p></td>
      </tr>)}</tbody>
    </table></div>}
    <Pager page={page} totalPages={data?.totalPages ?? 0} onChange={setPage} />
  </section>;
}

export function AdminJudgePage() {
  const filter = useWorkspace((state) => state.judgeJobFilter);
  const setFilter = useWorkspace((state) => state.setJudgeJobFilter);
  const setPage = useWorkspace((state) => state.setViewPage);
  const [error, setError] = useState("");
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const contextId = String(form.get("contextId") ?? "").trim();
    if (contextId && (!/^[1-9]\d{0,18}$/.test(contextId) || BigInt(contextId) > 9223372036854775807n)) {
      setError("문맥 ID는 양의 정수 ID를 입력해 주세요."); return;
    }
    setError(""); setPage("admin-judge-jobs", 0);
    setFilter({ status: String(form.get("status")), context: String(form.get("context")), contextId,
      failureCode: String(form.get("failureCode") ?? "").trim(), sort: String(form.get("sort")) });
  }
  return <div className="container stack">
    <Heading title="채점 작업 관리" />
    <form className="card row wrap" onSubmit={apply}>
      <label className="field">작업 상태<select name="status" className="input" defaultValue={filter.status ?? ""}><option value="">전체</option>{["PENDING", "RUNNING", "RETRY_WAITING", "SUCCEEDED", "FAILED"].map((v) => <option key={v}>{v}</option>)}</select></label>
      <label className="field">제출 문맥<select name="context" className="input" defaultValue={filter.context ?? ""}><option value="">전체</option>{["PRACTICE", "EXAM", "DIAGNOSIS", "CONTEST"].map((v) => <option key={v}>{v}</option>)}</select></label>
      <label className="field">문맥 ID<input name="contextId" className="input" defaultValue={filter.contextId ?? ""} inputMode="numeric" maxLength={19} /></label>
      <label className="field">실패 코드<input name="failureCode" className="input" defaultValue={filter.failureCode ?? ""} pattern="[A-Z0-9_]{1,100}" maxLength={100} /></label>
      <label className="field">정렬<select name="sort" className="input" defaultValue={filter.sort ?? "updatedAt,desc"}>
        <option value="updatedAt,desc">최근 갱신순</option><option value="updatedAt,asc">오래된 갱신순</option>
        <option value="createdAt,desc">최근 생성순</option><option value="createdAt,asc">오래된 생성순</option>
      </select></label><Button type="submit">필터 적용</Button>
    </form>
    {error && <p role="alert">{error}</p>}
    <JobList key={JSON.stringify(filter)} filter={filter} />
  </div>;
}
