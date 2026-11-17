"use client";
import { useState } from "react";
import { problemApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { ProblemListItem } from "@/services/types";
import { Badge, Button, Empty, Loading } from "@/components/ui";
import { isAssignmentDifficultyAllowed } from "@/features/study-assignment-policy";

export function AssignmentProblemPicker({ limit, selected, onChange }: {
  limit: number;
  selected: ProblemListItem[];
  onChange: (problems: ProblemListItem[]) => void;
}) {
  const [keyword, setKeyword] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const results = useResource(
    () => problemApi.getList({ keyword: query, page, size: 10 }),
    `${query}:${page}`,
  );
  return (
    <section className="stack-sm" aria-label="문제 선택">
      <h2>문제 선택 ({selected.length}/20)</h2>
      <p className="muted">태그와 개인 레벨에 관계없이 난이도 1~{limit} 문제를 새로 배정할 수 있습니다.</p>
      <div className="row">
        <label className="field grow">문제 검색
          <input className="input" value={keyword} placeholder="문제 제목 검색"
            onChange={(event) => setKeyword(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault(); setQuery(keyword.trim()); setPage(0);
              }
            }} />
        </label>
        <Button type="button" onClick={() => { setQuery(keyword.trim()); setPage(0); }}>검색</Button>
      </div>
      {results.loading ? <Loading /> : results.error ? (
        <div role="alert"><p>{results.error.message}</p><Button type="button" onClick={results.retry}>문제 목록 다시 시도</Button></div>
      ) : results.data && (
        <>
          <div className="table-wrap">
            <table>
              <thead><tr><th>문제</th><th>난이도</th><th>알고리즘</th><th>선택</th></tr></thead>
              <tbody>{results.data.content.map((problem) => {
                const added = selected.some((item) => item.problemId === problem.problemId);
                const allowed = isAssignmentDifficultyAllowed(problem.difficulty, limit);
                return <tr key={problem.problemId}>
                  <td>#{problem.problemId} {problem.title}</td>
                  <td><Badge>Lv. {problem.difficulty}</Badge></td>
                  <td>{problem.tags.map((tag) => tag.name).join(", ")}</td>
                  <td><Button type="button" className="small"
                    aria-label={`${problem.title} 추가`}
                    disabled={added || !allowed || selected.length >= 20}
                    onClick={() => onChange([...selected, problem])}>
                    {added ? "선택됨" : !allowed ? "난이도 상한 초과" : "추가"}
                  </Button></td>
                </tr>;
              })}</tbody>
            </table>
          </div>
          {results.data.content.length === 0 && <Empty title="검색 결과가 없어요" />}
          <div className="row">
            <Button type="button" disabled={results.data.page === 0} onClick={() => setPage(page - 1)}>이전</Button>
            <span>{results.data.page + 1} / {Math.max(1, results.data.totalPages)}</span>
            <Button type="button" disabled={!results.data.hasNext} onClick={() => setPage(page + 1)}>다음</Button>
          </div>
        </>
      )}
      <h3>선택한 문제</h3>
      {selected.length === 0 ? <p className="muted">문제를 1개 이상 선택해 주세요.</p> : (
        <ul className="stack-sm">{selected.map((problem) => (
          <li className="row between" key={problem.problemId}>
            <span>#{problem.problemId} {problem.title} · Lv. {problem.difficulty}
              {!isAssignmentDifficultyAllowed(problem.difficulty, limit) && " · 현재 난이도 상한 초과"}</span>
            <Button type="button" className="small" aria-label={`${problem.title} 제거`}
              onClick={() => onChange(selected.filter((item) => item.problemId !== problem.problemId))}>제거</Button>
          </li>
        ))}</ul>
      )}
    </section>
  );
}
