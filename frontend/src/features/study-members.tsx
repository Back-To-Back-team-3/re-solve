"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type {
  GoalType,
  MembershipRole,
  ServerPage,
  StudyDetail,
  StudyMemberItem,
} from "@/services/types";
import { Badge, Button, ErrorState, Loading, useAction } from "@/components/ui";
import { Pet } from "@/components/pet";
import { DelegateModal, RemoveModal } from "./study-member-modals";

const goalLabel: Record<GoalType, string> = {
  EMPLOYMENT: "취업 준비",
  CONTEST: "대회 준비",
  STUDY: "순수 학습",
};

const roleLabel: Record<MembershipRole, string> = {
  LEADER: "스터디장",
  MANAGER: "운영진",
  MEMBER: "멤버",
};

function roleTone(role: MembershipRole): string {
  if (role === "LEADER") return "accent";
  if (role === "MANAGER") return "success";
  return "";
}

export function MembersTab({
  studyId,
  myMembership,
  version,
  onRefresh,
}: {
  studyId: string;
  myMembership: StudyDetail["myMembership"];
  version: number;
  onRefresh: () => void;
}) {
  const router = useRouter();
  const action = useAction();
  const myId = myMembership?.membershipId ?? "";
  const isLeader = myMembership?.role === "LEADER";
  const isManager = myMembership?.role === "MANAGER";
  const isManagerOrLeader = isLeader || isManager;

  const [delegateTarget, setDelegateTarget] = useState<{
    membershipId: string;
    nickname: string;
  } | null>(null);
  const [removeTarget, setRemoveTarget] = useState<{
    membershipId: string;
    nickname: string;
  } | null>(null);
  const [openMenu, setOpenMenu] = useState<string | null>(null);

  const approved = useResource(
    () => studyApi.getMembers(studyId, { status: "APPROVED", size: 20 }),
    `${studyId}:approved:${version}`,
  );
  const pending = useResource(
    () =>
      isManagerOrLeader
        ? studyApi.getMembers(studyId, { status: "PENDING", size: 20 })
        : Promise.resolve({
            content: [],
            page: 0,
            size: 20,
            totalElements: 0,
            totalPages: 0,
            hasNext: false,
          } as ServerPage<StudyMemberItem>),
    `${studyId}:pending:${version}:${String(isManagerOrLeader)}`,
  );

  const pendingContent = pending.data?.content ?? [];
  const approvedContent = approved.data?.content ?? [];

  async function doApprove(membershipId: string) {
    await action(
      async () => {
        try {
          await studyApi.approveMember(studyId, membershipId);
          onRefresh();
          router.refresh();
        } catch (err) {
          const code = (err as { code?: string }).code;
          throw new Error(
            code === "STUDY_APPLICATION_EXPIRED"
              ? "만료된 신청입니다."
              : code === "STUDY_FULL"
                ? "정원이 가득 찼습니다."
                : ((err as { message?: string }).message ?? "오류가 발생했습니다."),
          );
        }
      },
      "승인했습니다.",
      false,
    );
  }

  async function doReject(membershipId: string) {
    await action(
      async () => {
        try {
          await studyApi.rejectMember(studyId, membershipId);
          onRefresh();
          router.refresh();
        } catch (err) {
          throw new Error(((err as { message?: string }).message ?? "오류가 발생했습니다."));
        }
      },
      "거절했습니다.",
      false,
    );
  }

  async function doChangeRole(membershipId: string, role: "MANAGER" | "MEMBER") {
    setOpenMenu(null);
    await action(
      async () => {
        try {
          await studyApi.changeRole(studyId, membershipId, role);
          onRefresh();
          router.refresh();
        } catch (err) {
          const code = (err as { code?: string }).code;
          throw new Error(
            code === "STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED"
              ? "스터디장 역할은 위임 메뉴를 이용하세요."
              : code === "STUDY_MANAGER_LIMIT_EXCEEDED"
                ? "운영진은 최대 2명까지 가능합니다."
                : ((err as { message?: string }).message ?? "오류가 발생했습니다."),
          );
        }
      },
      "역할이 변경됐습니다.",
      false,
    );
  }

  return (
    <div className="stack">
      {isManagerOrLeader && (
        <section className="stack">
          <h3>가입 신청 ({pending.loading ? "…" : pendingContent.length})</h3>
          {pending.loading && <Loading />}
          {pending.error && (
            <ErrorState message={pending.error.message} retry={pending.retry} />
          )}
          {!pending.loading && !pending.error && pendingContent.length === 0 && (
            <p className="muted">대기 중인 신청이 없습니다.</p>
          )}
          {!pending.loading &&
            !pending.error &&
            pendingContent.map((m) => (
              <div className="row between inset" key={m.membershipId}>
                <div className="row">
                  <Pet size={36} />
                  <div>
                    <b>{m.nickname}</b>
                    {m.learningProfile && (
                      <p className="muted">
                        {goalLabel[m.learningProfile.goal]} ·{" "}
                        {Object.entries(m.learningProfile.tagLevels)
                          .slice(0, 2)
                          .map(([t, l]) => `${t} ${l}`)
                          .join(" · ")}
                      </p>
                    )}
                    {m.expiresAt && <p className="muted">만료 {m.expiresAt.slice(0, 10)}</p>}
                  </div>
                </div>
                <div className="row">
                  <Button onClick={() => doApprove(m.membershipId)}>승인</Button>
                  <Button onClick={() => doReject(m.membershipId)}>거절</Button>
                </div>
              </div>
            ))}
        </section>
      )}

      <section className="stack">
        <h3>구성원 ({approved.loading ? "…" : approvedContent.length})</h3>
        {approved.loading && <Loading />}
        {approved.error && (
          <ErrorState message={approved.error.message} retry={approved.retry} />
        )}
        {!approved.loading &&
          !approved.error &&
          approvedContent.map((m) => {
            const isSelf = m.membershipId === myId;
            const canLead = isLeader && !isSelf;
            const canRemoveOnly = isManager && m.role === "MEMBER" && !isSelf;
            const hasMenu = canLead || canRemoveOnly;
            return (
              <div className="row between inset" key={m.membershipId}>
                <div className="row">
                  <Pet size={36} />
                  <div>
                    <b>{m.nickname}</b>
                    {isSelf && <span className="muted"> (나)</span>}
                    {m.joinedAt && <p className="muted">{m.joinedAt.slice(0, 10)}</p>}
                  </div>
                </div>
                <div className="row">
                  <Badge tone={roleTone(m.role)}>{roleLabel[m.role]}</Badge>
                  {hasMenu && (
                    <div className="relative">
                      {openMenu === m.membershipId && (
                        <div
                          className="fixed inset-0 z-10"
                          onClick={() => setOpenMenu(null)}
                        />
                      )}
                      <Button
                        onClick={() =>
                          setOpenMenu(openMenu === m.membershipId ? null : m.membershipId)
                        }
                        aria-label="메뉴 열기"
                      >
                        ···
                      </Button>
                      {openMenu === m.membershipId && (
                        <div className="card stack-sm absolute right-0 top-full z-20 min-w-32">
                          {canLead && m.role === "MEMBER" && (
                            <button
                              className="btn"
                              onClick={() => doChangeRole(m.membershipId, "MANAGER")}
                            >
                              운영진 임명
                            </button>
                          )}
                          {canLead && m.role === "MANAGER" && (
                            <button
                              className="btn"
                              onClick={() => doChangeRole(m.membershipId, "MEMBER")}
                            >
                              운영진 해제
                            </button>
                          )}
                          {canLead && (
                            <button
                              className="btn"
                              onClick={() => {
                                setOpenMenu(null);
                                setDelegateTarget({
                                  membershipId: m.membershipId,
                                  nickname: m.nickname,
                                });
                              }}
                            >
                              위임
                            </button>
                          )}
                          {(canLead || canRemoveOnly) && (
                            <button
                              className="btn"
                              onClick={() => {
                                setOpenMenu(null);
                                setRemoveTarget({
                                  membershipId: m.membershipId,
                                  nickname: m.nickname,
                                });
                              }}
                            >
                              강제 탈퇴
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
      </section>

      {delegateTarget && (
        <DelegateModal
          studyId={studyId}
          target={delegateTarget}
          onClose={() => setDelegateTarget(null)}
          onRefresh={onRefresh}
        />
      )}

      {removeTarget && (
        <RemoveModal
          studyId={studyId}
          target={removeTarget}
          onClose={() => setRemoveTarget(null)}
          onRefresh={onRefresh}
        />
      )}
    </div>
  );
}
