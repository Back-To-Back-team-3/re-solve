"use client";
import Link from "next/link";
import { useState, useEffect } from "react";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import { Search } from "lucide-react";
import { api, labels, solvedStatus } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import type { Problem, Database, Submission } from "@/services/types";
import { useCatalog } from "@/services/catalog";
import {
  Badge,
  Button,
  Empty,
  ErrorState,
  Heading,
  Loading,
  date,
} from "@/components/ui";
export function ProblemTable({
  items,
  db,
}: {
  items: Problem[];
  db?: Database;
}) {
  const { tags } = useCatalog();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>상태</th>
            <th>번호</th>
            <th>문제 제목</th>
            <th>난이도</th>
            <th>알고리즘</th>
            <th>정답률</th>
          </tr>
        </thead>
        <tbody>
          {items.map((p) => {
            const status = db
              ? solvedStatus(p.id, db.submissions, db.currentUserId)
              : "UNSOLVED";
            return (
              <tr key={p.id}>
                <td>
                  <Badge
                    tone={
                      status === "SOLVED"
                        ? "success"
                        : status === "WRONG"
                          ? "error"
                          : ""
                    }
                  >
                    {labels[status]}
                  </Badge>
                </td>
                <td className="mono">{p.id}</td>
                <td>
                  <Link href={`/problems/${p.id}`}>{p.title}</Link>
                </td>
                <td>
                  <Badge>Lv. {p.difficulty}</Badge>
                </td>
                <td>{tags.find((t) => t.id === p.tagId)?.name}</td>
                <td className="mono">{p.rate}%</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
export function SubmissionList({ items }: { items: Submission[] }) {
  const { problems } = useCatalog();
  if (!items.length)
    return (
      <Empty
        title="제출 기록이 없어요"
        description="문제를 제출하면 여기에 결과가 남아요."
      />
    );
  return (
    <div>
      {items.slice(0, 8).map((s) => (
        <div className="activity-row" key={s.id}>
          <Badge
            tone={
              s.verdict === "AC"
                ? "success"
                : s.state === "FAILED" || s.verdict
                  ? "error"
                  : "pending"
            }
          >
            {labels[s.verdict || s.state]}
          </Badge>
          <div className="grow">
            <Link href={`/problems/${s.problemId}`}>
              <b>{problems.find((p) => p.id === s.problemId)?.title}</b>
            </Link>
            <p className="muted">
              {labels[s.language]} · {s.kind === "run" ? "예제 실행" : "제출"} ·{" "}
              {date(s.createdAt)}
            </p>
          </div>
          <span className="badge">모의</span>
        </div>
      ))}
    </div>
  );
}
export function useQuery() {
  const params = useSearchParams(),
    router = useRouter(),
    path = usePathname();
  return {
    params,
    change: (values: Record<string, string>) => {
      const next = new URLSearchParams(params);
      Object.entries(values).forEach(([k, v]) =>
        v ? next.set(k, v) : next.delete(k),
      );
      router.push(`${path}?${next}`);
    },
  };
}
export function pageSlice<T>(items: T[], raw: string | null, size = 6) {
  const requested = Number(raw || 0);
  const page = Math.min(
    Math.max(0, Number.isFinite(requested) ? Math.floor(requested) : 0),
    Math.max(0, Math.ceil(items.length / size) - 1),
  );
  return {
    items: items.slice(page * size, (page + 1) * size),
    page,
    totalPages: Math.ceil(items.length / size),
  };
}
export function Pager({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  if (totalPages < 2) return null;
  return (
    <div className="row" style={{ justifyContent: "center" }}>
      <Button disabled={page === 0} onClick={() => onChange(page - 1)}>
        이전
      </Button>
      <span>
        {page + 1} / {totalPages}
      </span>
      <Button
        disabled={page >= totalPages - 1}
        onClick={() => onChange(page + 1)}
      >
        다음
      </Button>
    </div>
  );
}
export function ProblemsPage() {
  const { tags } = useCatalog();
  const { params, change } = useQuery();
  const [keyword, setKeyword] = useState(params.get("keyword") || "");
  const appliedKeyword = params.get("keyword") || "";
  useEffect(() => setKeyword(appliedKeyword), [appliedKeyword]);
  const filter = {
    keyword: params.get("keyword") || "",
    tag: params.get("tag") || "",
    difficulty: params.get("difficulty") || "",
    status: params.get("status") || "",
    sort: params.get("sort") || "",
    page: Number(params.get("page") || 0),
  };
  const resource = useResource(
    async () => ({
      page: await api.problems(filter),
      db: await api.snapshot(),
    }),
    params.toString(),
  );
  return (
    <div className="container stack">
      <Heading
        title="문제 풀기"
        eyebrow="PROBLEM ARCADE"
        description="한 문제씩 해결하며, 나만의 알고리즘 펫을 키워 보세요."
      />
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          change({ keyword, page: "0" });
        }}
      >
        <label className="search row">
          <span className="visually-hidden">문제 검색</span>
          <input
            className="input"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="문제 제목 또는 번호 검색"
          />
          <Button aria-label="검색">
            <Search size={18} />
          </Button>
        </label>
        {[
          {
            key: "tag",
            label: "모든 알고리즘",
            options: tags.map((t) => [t.id, t.name]),
          },
          {
            key: "difficulty",
            label: "모든 난이도",
            options: [1, 2, 3, 4, 5].map((n) => [String(n), `Lv. ${n}`]),
          },
          {
            key: "status",
            label: "모든 상태",
            options: ["UNSOLVED", "SOLVED", "WRONG"].map((s) => [s, labels[s]]),
          },
          {
            key: "sort",
            label: "최신순",
            options: [
              ["difficulty", "쉬운 문제순"],
              ["rate", "정답률순"],
            ],
          },
        ].map((f) => (
          <select
            key={f.key}
            aria-label={f.label}
            className="input"
            value={params.get(f.key) || ""}
            onChange={(e) => change({ [f.key]: e.target.value, page: "0" })}
          >
            <option value="">{f.label}</option>
            {f.options.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        ))}
      </form>
      {resource.loading ? (
        <Loading />
      ) : resource.error ? (
        <ErrorState message={resource.error} retry={resource.retry} />
      ) : (
        resource.data && (
          <>
            <div className="row between">
              <p>
                총 <b>{resource.data.page.totalElements}</b>문제
              </p>
              <span className="muted">목업 문제 · 함수형 풀이</span>
            </div>
            {resource.data.page.items.length ? (
              <ProblemTable
                items={resource.data.page.items}
                db={resource.data.db}
              />
            ) : (
              <Empty
                title="검색 결과가 없어요"
                description="검색어나 필터를 바꿔 보세요."
                action={
                  <Button
                    onClick={() => {
                      setKeyword("");
                      change({
                        keyword: "",
                        tag: "",
                        difficulty: "",
                        status: "",
                        page: "0",
                      });
                    }}
                  >
                    필터 초기화
                  </Button>
                }
              />
            )}
            <div className="row" style={{ justifyContent: "center" }}>
              <Button
                disabled={resource.data.page.page === 0}
                onClick={() =>
                  change({ page: String(resource.data!.page.page - 1) })
                }
              >
                이전
              </Button>
              {Array.from({ length: resource.data.page.totalPages }, (_, i) => (
                <Button
                  className={i === resource.data?.page.page ? "primary" : ""}
                  key={i}
                  aria-label={`${i + 1} 페이지`}
                  onClick={() => change({ page: String(i) })}
                >
                  {i + 1}
                </Button>
              ))}
              <Button
                disabled={
                  resource.data.page.page >= resource.data.page.totalPages - 1
                }
                onClick={() =>
                  change({ page: String(resource.data!.page.page + 1) })
                }
              >
                다음
              </Button>
            </div>
          </>
        )
      )}
    </div>
  );
}
