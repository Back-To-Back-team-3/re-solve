"use client";
import Link from "next/link";
import { Badge, ErrorState } from "@/components/ui";
import { useProblemLevels } from "@/features/problem-access";
import { problemAccess } from "@/services/problem-access";
import { labelOf, solvedStatusLabel } from "@/services/labels";
import type { ProblemListItem } from "@/services/types";

export function ProblemTable({ items }: { items: ProblemListItem[] }) {
  const levels = useProblemLevels();
  return (
    <div className="table-wrap">
      {levels.error && <ErrorState message="레벨을 확인하지 못했습니다." retry={levels.retry} />}
      <table>
        <thead>
          <tr>
            <th>상태</th>
            <th>번호</th>
            <th>문제 제목</th>
            <th>난이도</th>
            <th>알고리즘</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => {
            const access = problemAccess(p, levels.data?.content ?? []);
            return (
            <tr key={p.problemId}>
              <td>
                <Badge tone={p.solvedStatus === "SOLVED" ? "success" : ""}>
                  {labelOf(solvedStatusLabel, p.solvedStatus)}
                </Badge>
              </td>
              <td className="mono">{p.problemId}</td>
              <td>
                <Link href={`/problems/${p.problemId}`}>{p.title}</Link>
                <div className="muted">
                  {!levels.memberId ? "로그인 후 레벨 확인" : levels.error ? "레벨 확인 불가"
                    : levels.loading ? "레벨 확인 중…"
                    : access.allowed ? "풀이 가능"
                    : `레벨 잠금 · ${access.blockedTags.map((tag) => `${tag.name} Lv.${p.difficulty} 필요`).join(", ")}`}
                </div>
              </td>
              <td>
                <Badge>Lv. {p.difficulty}</Badge>
              </td>
              <td>{p.tags.map((t) => t.name).join(", ")}</td>
            </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
