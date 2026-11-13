"use client";
import { useState } from "react";
import { adminProblemApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { AdminProblemListItem, ProblemVisibility } from "@/services/types";
import {
  Badge,
  Button,
  Empty,
  ErrorState,
  Heading,
  Go,
  Loading,
  Modal,
} from "@/components/ui";
import { useToast } from "@/components/ui";
import { Pager } from "./problem-list";

const VISIBILITY_LABELS: Record<ProblemVisibility, string> = {
  PUBLIC: "공개",
  PRIVATE: "비공개",
  ARCHIVED: "보관",
};
function visibilityTone(v: ProblemVisibility): string {
  if (v === "PUBLIC") return "success";
  if (v === "ARCHIVED") return "error";
  return "";
}
type ArchivePending = { item: AdminProblemListItem; reason: string };
export function AdminProblemsPage() {
  const toast = useToast();
  const [visibility, setVisibility] = useState("");
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<AdminProblemListItem[]>([]);
  const [archive, setArchive] = useState<ArchivePending | null>(null);
  const [actionError, setActionError] = useState<Record<string, string>>({});
  const filter = { visibility: visibility || undefined, page, size: 20 };
  const r = useResource(
    () => adminProblemApi.getList(filter),
    `admin-problems-${visibility}-${page}`,
  );
  const items: AdminProblemListItem[] = rows.length
    ? rows
    : (r.data?.content ?? []);
  if (r.loading && !r.data) return <Loading />;
  if (r.error) return <ErrorState message={r.error.message} retry={r.retry} />;
  async function changeVisibility(
    item: AdminProblemListItem,
    next: ProblemVisibility,
  ) {
    setActionError((e) => ({ ...e, [item.problemId]: "" }));
    try {
      let result: { visibility: ProblemVisibility; rowVersion: number };
      if (next === "ARCHIVED") {
        setArchive({ item, reason: "" });
        return;
      } else if (next === "PUBLIC") {
        result = await adminProblemApi.publish(item.problemId, item.rowVersion);
      } else {
        result = await adminProblemApi.unpublish(item.problemId, item.rowVersion);
      }
      setRows((prev) => {
        const base = prev.length ? prev : (r.data?.content ?? []);
        return base.map((p) =>
          p.problemId === item.problemId
            ? { ...p, visibility: result.visibility, rowVersion: result.rowVersion }
            : p,
        );
      });
      toast("상태가 변경됐습니다.");
    } catch (e) {
      const err = e as { message?: string };
      setActionError((prev) => ({
        ...prev,
        [item.problemId]: err.message ?? "상태 변경에 실패했습니다.",
      }));
    }
  }
  async function confirmArchive() {
    if (!archive) return;
    const { item, reason } = archive;
    setArchive(null);
    try {
      const result = await adminProblemApi.archive(
        item.problemId,
        item.rowVersion,
        reason,
      );
      setRows((prev) => {
        const base = prev.length ? prev : (r.data?.content ?? []);
        return base.map((p) =>
          p.problemId === item.problemId
            ? { ...p, visibility: result.visibility, rowVersion: result.rowVersion }
            : p,
        );
      });
      toast("상태가 변경됐습니다.");
    } catch (e) {
      const err = e as { message?: string };
      setActionError((prev) => ({
        ...prev,
        [item.problemId]: err.message ?? "보관에 실패했습니다.",
      }));
    }
  }
  const totalPages = r.data?.totalPages ?? 1;
  const totalElements = r.data?.totalElements ?? 0;
  return (
    <div className="container stack">
      <Heading title="문제 관리" action={<Go href="/admin/problems/new">문제 등록</Go>} />
      <div className="row">
        <select
          aria-label="공개 상태 필터"
          className="input"
          value={visibility}
          onChange={(e) => { setVisibility(e.target.value); setPage(0); setRows([]); }}
        >
          <option value="">전체</option>
          <option value="PUBLIC">공개</option>
          <option value="PRIVATE">비공개</option>
          <option value="ARCHIVED">보관</option>
        </select>
        <span className="muted">{totalElements}개</span>
      </div>
      {!items.length ? (
        <Empty title="문제가 없어요" description="필터를 바꿔 보세요." />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>제목</th>
                <th>용도</th>
                <th>상태</th>
                <th>변경</th>
              </tr>
            </thead>
            <tbody>
              {items.map((p) => (
                <tr key={p.problemRevisionId}>
                  <td className="mono">{p.problemId}</td>
                  <td><Go href={`/admin/problems/${p.problemId}`}>{p.title}</Go></td>
                  <td>{p.scope === "GENERAL" ? "일반" : "시험전용"}</td>
                  <td>
                    <Badge tone={visibilityTone(p.visibility)}>
                      {VISIBILITY_LABELS[p.visibility]}
                    </Badge>
                  </td>
                  <td>
                    {p.visibility !== "ARCHIVED" && (
                      <select
                        aria-label="상태 변경"
                        className="input"
                        value={p.visibility}
                        onChange={(e) =>
                          changeVisibility(p, e.target.value as ProblemVisibility)
                        }
                      >
                        <option value="PUBLIC">공개</option>
                        <option value="PRIVATE">비공개</option>
                        <option value="ARCHIVED">보관</option>
                      </select>
                    )}
                    {actionError[p.problemId] && (
                      <p className="error-text">{actionError[p.problemId]}</p>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {totalPages > 1 && (
        <Pager
          page={page}
          totalPages={totalPages}
          onChange={(p) => { setPage(p); setRows([]); }}
        />
      )}
      <Modal
        open={!!archive}
        onOpenChange={(open) => { if (!open) setArchive(null); }}
        title="문제를 보관할까요?"
        description="보관된 문제는 새 풀이·시험 선택에서 제외됩니다. 이 작업은 되돌릴 수 없어요."
      >
        <div className="stack">
          <label className="field">
            보관 사유
            <input
              className="input"
              value={archive?.reason ?? ""}
              onChange={(e) =>
                setArchive((prev) => prev ? { ...prev, reason: e.target.value } : null)
              }
            />
          </label>
          <Button
            className="danger"
            disabled={!archive?.reason.trim()}
            onClick={confirmArchive}
          >
            보관
          </Button>
        </div>
      </Modal>
    </div>
  );
}
