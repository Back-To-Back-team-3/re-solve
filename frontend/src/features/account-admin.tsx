"use client";
import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { adminApi } from "@/services/api";
import type { UserStatus } from "@/services/types";
import { useResource } from "@/services/hooks";
import { useWorkspace } from "@/stores/workspace";
import {
  Button,
  Empty,
  ErrorState,
  Heading,
  Loading,
  Section,
  Tabs,
  useAction,
} from "@/components/ui";

const STATUS_TABS = [
  { id: "", label: "전체" },
  { id: "ACTIVE", label: "활성" },
  { id: "SUSPENDED", label: "정지" },
  { id: "WITHDRAWN", label: "탈퇴" },
] as const;

const PAGE_SIZE = 20;

export function AdminMembersPage() {
  const member = useWorkspace((s) => s.member);
  const router = useRouter();
  const params = useSearchParams();
  const action = useAction();

  const status = (params.get("status") ?? "") as UserStatus | "";
  const page = Number(params.get("page") ?? "1") - 1;

  useEffect(() => {
    if (!member) return;
    if (member.role !== "ADMIN") router.push("/dashboard");
  }, [member, router]);

  const membersR = useResource(
    () => adminApi.getMembers({ status: status || undefined, page, size: PAGE_SIZE }),
    `${status}-${page}`,
  );

  if (!member || member.role !== "ADMIN") return <Loading />;

  if (membersR.loading) return <Loading />;
  if (membersR.error)
    return <ErrorState message={membersR.error.message} retry={membersR.retry} />;

  const result = membersR.data!;

  function pushFilter(next: { status?: string; page?: number }) {
    const q = new URLSearchParams();
    const s = next.status !== undefined ? next.status : status;
    const p = next.page !== undefined ? next.page + 1 : 1;
    if (s) q.set("status", s);
    if (p > 1) q.set("page", String(p));
    router.push(`?${q.toString()}`);
  }

  return (
    <div className="container stack">
      <Heading
        title="회원 관리"
        eyebrow="ADMIN"
        description="회원 상태와 권한을 관리합니다."
      />
      <Tabs
        value={status}
        onChange={(id) => pushFilter({ status: id })}
        items={STATUS_TABS.map((t) => ({ id: t.id, label: t.label }))}
      />
      <Section title={`전체 ${result.totalElements}명`}>
        {result.content.length === 0 && <Empty title="해당 조건의 회원이 없습니다." />}
        <div className="stack">
          {result.content.map((m) => (
            <div key={m.memberId} className="card row">
              <div>
                <span>{m.nickname}</span>
                <span className="muted"> @{m.githubLogin}</span>
              </div>
              <div className="row">
                <span className="badge">{m.role}</span>
                <span className="badge">{m.status}</span>
                {m.status === "ACTIVE" && (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          adminApi
                            .updateStatus(m.memberId, "SUSPENDED", "관리자 조치")
                            .then(() => router.refresh()),
                        "정지 처리했습니다.",
                      )
                    }
                  >
                    정지
                  </Button>
                )}
                {m.status === "SUSPENDED" && (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          adminApi
                            .updateStatus(m.memberId, "ACTIVE", "정지 해제")
                            .then(() => router.refresh()),
                        "정지를 해제했습니다.",
                      )
                    }
                  >
                    정지 해제
                  </Button>
                )}
                {m.role === "USER" && (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          adminApi
                            .updateRole(m.memberId, "ADMIN", "관리자 권한 부여")
                            .then(() => router.refresh()),
                        "관리자 권한을 부여했습니다.",
                      )
                    }
                  >
                    관리자 지정
                  </Button>
                )}
                {m.role === "ADMIN" && (
                  <Button
                    onClick={() =>
                      action(
                        () =>
                          adminApi
                            .updateRole(m.memberId, "USER", "관리자 권한 회수")
                            .then(() => router.refresh()),
                        "관리자 권한을 회수했습니다.",
                      )
                    }
                  >
                    권한 회수
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
        {(page > 0 || result.hasNext) && (
          <div className="row">
            <Button disabled={page === 0} onClick={() => pushFilter({ page: page - 1 })}>
              이전
            </Button>
            <span className="muted">
              {page + 1} / {result.totalPages}
            </span>
            <Button disabled={!result.hasNext} onClick={() => pushFilter({ page: page + 1 })}>
              다음
            </Button>
          </div>
        )}
      </Section>
    </div>
  );
}
