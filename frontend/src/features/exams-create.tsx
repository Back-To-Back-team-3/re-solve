"use client";
import { useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Button, ErrorState, Go, Heading, Loading, useAction } from "@/components/ui";
import { contestApi, problemApi, studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { examCreateProblems } from "@/services/exam-create";
import type { ProblemListItem } from "@/services/types";

type SelectedProblem = Pick<ProblemListItem, "problemId" | "title"> & { score: string };

export function ExamCreate() {
  const router = useRouter();
  const studyId = useSearchParams().get("studyId") ?? "";
  const action = useAction();
  const study = useResource(() => studyId ? studyApi.getDetail(studyId) : Promise.resolve(null), studyId);
  const [keyword, setKeyword] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<SelectedProblem[]>([]);
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const problems = useResource(() => problemApi.getList({ keyword: query, page, size: 10 }), `${query}:${page}`);
  const membership = study.data?.myMembership;
  const canCreate = membership?.status === "APPROVED" && (membership.role === "LEADER" || membership.role === "MANAGER")
    && study.data?.status !== "CLOSED";
  if (study.loading) return <div className="container"><Loading /></div>;
  if (study.error) return <div className="container"><ErrorState message={study.error.message} retry={study.retry} /></div>;
  if (!canCreate) return <div className="container stack"><p>진행 중인 스터디의 스터디장·운영진만 시험을 만들 수 있습니다.</p><Go href="/studies">스터디 목록</Go></div>;

  function search() { setPage(0); setQuery(keyword.trim()); }
  return <div className="container stack">
    <Heading title="모의 코테 만들기" eyebrow="NEW CHALLENGE" description="공개 문제를 선택하고 배점과 시간을 정해 보세요." />
    <form className="card form-card stack" onSubmit={async (event) => {
      event.preventDefault();
      if (sending.current) return;
      const fields = new FormData(event.currentTarget);
      sending.current = true; setBusy(true);
      try {
        await action(async () => {
          const startsAt = new Date(String(fields.get("startsAt")));
          const endsAt = new Date(String(fields.get("endsAt")));
          const duration = endsAt.getTime() - startsAt.getTime();
          if (!Number.isFinite(duration) || startsAt.getTime() <= Date.now() || duration < 60000 || duration > 180 * 60000) {
            throw new Error("시작은 현재 이후, 시험 시간은 1~180분으로 설정해 주세요.");
          }
          const result = await contestApi.createExam(studyId, {
            title: String(fields.get("title")).trim(), mode: "FIXED", startsAt: startsAt.toISOString(), endsAt: endsAt.toISOString(),
            problems: examCreateProblems(selected),
          });
          router.push(`/exams/${result.examId}`);
        }, "모의 코테를 만들었습니다.");
      } finally { sending.current = false; setBusy(false); }
    }}>
      <fieldset className="stack m-0 min-w-0 border-0 p-0" disabled={busy}>
        <label className="field">시험 이름<input className="input" name="title" required minLength={1} maxLength={200} /></label>
        <div className="form-grid">
          <label className="field">시작 일시<input className="input" name="startsAt" type="datetime-local" required /></label>
          <label className="field">종료 일시<input className="input" name="endsAt" type="datetime-local" required /></label>
        </div>
        <section className="stack-sm" aria-label="공개 문제 선택">
          <h3>문제 선택 · {selected.length}/20</h3>
          <div className="row wrap">
            <label className="field grow">문제 검색<input className="input" value={keyword} placeholder="문제 제목 또는 번호"
              onChange={(event) => setKeyword(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); search(); } }} /></label>
            <Button type="button" onClick={search}>검색</Button>
          </div>
          {problems.loading ? <Loading /> : problems.error ? <ErrorState message={problems.error.message} retry={problems.retry} />
            : <div className="stack-sm">{problems.data?.content.map((problem) => {
              const added = selected.some((item) => item.problemId === problem.problemId);
              return <div className="row between wrap" key={problem.problemId}>
                <span>#{problem.problemId} {problem.title} · Lv.{problem.difficulty}</span>
                <Button type="button" className="small" disabled={added || selected.length >= 20}
                  aria-label={`${problem.title} 선택`} onClick={() => setSelected((items) => [...items, {
                    problemId: problem.problemId, title: problem.title, score: "100.00",
                  }])}>{added ? "선택됨" : "선택"}</Button>
              </div>;
            })}{!problems.data?.content.length && <p>검색 결과가 없습니다.</p>}</div>}
          <div className="row">
            <Button type="button" disabled={page === 0 || problems.loading} onClick={() => setPage((value) => value - 1)}>이전</Button>
            <span>{page + 1}페이지</span>
            <Button type="button" disabled={!problems.data?.hasNext || problems.loading} onClick={() => setPage((value) => value + 1)}>다음</Button>
          </div>
        </section>
        <div className="overflow-x-auto">
          <table aria-label="선택한 시험 문제" className="w-full"><thead><tr><th>순서</th><th>문제</th><th>배점</th><th>관리</th></tr></thead>
            <tbody>{selected.map((problem, index) => <tr key={problem.problemId}>
              <th scope="row">{index + 1}</th><td>{problem.title}</td>
              <td><input className="input min-w-28" inputMode="decimal" aria-label={`${problem.title} 배점`} value={problem.score}
                onChange={(event) => setSelected((items) => items.map((item) => item.problemId === problem.problemId
                  ? { ...item, score: event.target.value } : item))} /></td>
              <td><Button type="button" className="small" aria-label={`${problem.title} 제거`}
                onClick={() => setSelected((items) => items.filter((item) => item.problemId !== problem.problemId))}>제거</Button></td>
            </tr>)}</tbody></table>
        </div>
        {!selected.length && <p>문제를 1개 이상 선택해 주세요.</p>}
        <small className="muted">문제별 0.01~9999.99점, 합계 9999.99점 이하로 설정합니다.</small>
        <div className="row"><Go href={`/studies/${studyId}`}>취소</Go><Button className="primary grow" disabled={busy || !selected.length}>시험 생성</Button></div>
      </fieldset>
    </form>
  </div>;
}
