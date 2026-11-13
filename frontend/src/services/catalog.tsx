"use client";
import { createContext, useContext, type ReactNode } from "react";
import { problemApi, problemTagApi } from "./api";
import { useResource } from "./hooks";
import { ErrorState, Loading } from "@/components/ui";
import type { Problem, Tag } from "./types";
type Catalog = { tags: Tag[]; problems: Problem[] };
const Context = createContext<Catalog | null>(null);
// 태그 목록(§2.5.1)과 문제 목록(§2.1.1, size 100)을 병렬로 조회해 카탈로그를 구성한다.
async function fetchCatalog(): Promise<Catalog> {
  const [tagsRes, problemsRes] = await Promise.all([
    problemTagApi.getTags(),
    problemApi.getList({ size: 100 }),
  ]);
  return {
    tags: tagsRes.content.map((t) => ({
      id: t.tagId,
      name: t.name,
      pet: t.name,
      level: 0,
      ac: 0,
      rate: 0,
    })),
    problems: problemsRes.content.map((p) => ({
      id: p.problemId,
      problemRevisionId: p.problemRevisionId,
      title: p.title,
      difficulty: p.difficulty,
      tags: p.tags,
      description: "",
      constraints: "",
      examples: "",
      functionSpec: { name: "solution", parameters: [], returnType: "" },
      starterCodes: { java: "", python3: "", cpp: "" },
      samples: [],
    })),
  };
}
export function CatalogProvider({ children }: { children: ReactNode }) {
  const r = useResource(fetchCatalog);
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
  return <Context.Provider value={r.data!}>{children}</Context.Provider>;
}
export function useCatalog() {
  return useContext(Context) ?? { tags: [], problems: [] };
}
