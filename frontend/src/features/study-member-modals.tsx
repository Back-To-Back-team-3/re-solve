"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { studyApi } from "@/services/api";
import { Button, useAction } from "@/components/ui";

export function DelegateModal({
  studyId,
  target,
  onClose,
  onRefresh,
}: {
  studyId: string;
  target: { membershipId: string; nickname: string };
  onClose: () => void;
  onRefresh: () => void;
}) {
  const action = useAction();
  const router = useRouter();

  async function doDelegate() {
    await action(
      async () => {
        try {
          await studyApi.delegateLeader(studyId, target.membershipId);
          onClose();
          onRefresh();
          router.refresh();
        } catch (err) {
          throw new Error(((err as { message?: string }).message ?? "오류가 발생했습니다."));
        }
      },
      "스터디장을 위임했습니다.",
      false,
    );
  }

  return (
    <>
      <div className="modal-overlay" onClick={onClose} />
      <div className="modal stack">
        <h2 className="modal-title">스터디장 위임</h2>
        <p className="modal-description">
          {target.nickname}님에게 스터디장을 위임합니다.
          <br />
          위임 후에는 되돌릴 수 없습니다.
        </p>
        <div className="row">
          <Button onClick={onClose}>취소</Button>
          <Button className="primary" onClick={doDelegate}>
            위임하기
          </Button>
        </div>
      </div>
    </>
  );
}

export function RemoveModal({
  studyId,
  target,
  onClose,
  onRefresh,
}: {
  studyId: string;
  target: { membershipId: string; nickname: string };
  onClose: () => void;
  onRefresh: () => void;
}) {
  const [reason, setReason] = useState("");
  const action = useAction();
  const router = useRouter();

  async function doRemove() {
    await action(
      async () => {
        try {
          await studyApi.removeMember(studyId, target.membershipId, reason);
          onClose();
          onRefresh();
          router.refresh();
        } catch (err) {
          throw new Error(((err as { message?: string }).message ?? "오류가 발생했습니다."));
        }
      },
      "강제 탈퇴 처리했습니다.",
      false,
    );
  }

  return (
    <>
      <div className="modal-overlay" onClick={onClose} />
      <div className="modal stack">
        <h2 className="modal-title">강제 탈퇴</h2>
        <p className="modal-description">{target.nickname}님을 강제 탈퇴합니다.</p>
        <label className="field">
          사유 (필수, 최대 500자)
          <textarea
            className="input"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="row">
          <Button onClick={onClose}>취소</Button>
          <Button className="primary" disabled={!reason.trim()} onClick={doRemove}>
            탈퇴 처리
          </Button>
        </div>
      </div>
    </>
  );
}
