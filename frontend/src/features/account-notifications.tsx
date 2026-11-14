"use client";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { notificationApi, type ApiError } from "@/services/api";
import type { NotificationPreferences } from "@/services/types";
import { Badge, Button, ErrorState, Loading, Section, useToast } from "@/components/ui";

export interface NotificationPreferenceItemMeta {
  key: keyof NotificationPreferences;
  label: string;
  description: string;
}

export const NOTIFICATION_PREFERENCE_ITEMS: readonly NotificationPreferenceItemMeta[] = [
  {
    key: "isExamContestEnabled",
    label: "시험·대회 알림",
    description: "시험 및 대회 시작 임박, 결과 확정, 일정 변경, 취소 안내를 받습니다.",
  },
  {
    key: "isStudyApplicationEnabled",
    label: "스터디 가입 신청 알림",
    description: "스터디 가입 신청 승인·거절 결과를 받습니다.",
  },
  {
    key: "isAssignmentEnabled",
    label: "스터디 과제 알림",
    description: "문제집 배정, 마감 임박, 미완료 반복 안내를 받습니다.",
  },
  {
    key: "isLevelChangedEnabled",
    label: "태그 레벨 변경 알림",
    description: "진단 결과, 태그 레벨 승급·변경 및 재응시 쿨타임 해제 안내를 받습니다.",
  },
  {
    key: "isHintReadyEnabled",
    label: "AI 힌트 준비 알림",
    description: "요청한 단계별 AI 힌트 생성이 완료되면 알림을 받습니다.",
  },
  {
    key: "isGithubSyncEnabled",
    label: "GitHub 동기화 알림",
    description: "정답 코드의 GitHub 저장소 자동 커밋이 최종 실패했을 때 알림을 받습니다.",
  },
];

export function NotificationPreferencesSection() {
  const router = useRouter();
  const toast = useToast();
  const [preferences, setPreferences] = useState<NotificationPreferences | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savingKey, setSavingKey] = useState<keyof NotificationPreferences | null>(null);
  const [error, setError] = useState<string | null>(null);

  function loadPreferences() {
    setLoading(true);
    setLoadError(null);
    notificationApi
      .getPreferences()
      .then((res) => setPreferences(res))
      .catch((e: unknown) => {
        const apiErr = e as Partial<ApiError> | undefined;
        setLoadError(apiErr?.message ?? "알림 수신 설정을 불러오지 못했습니다.");
      })
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    loadPreferences();
  }, []);

  async function handleToggle(key: keyof NotificationPreferences, nextValue: boolean) {
    if (!preferences || savingKey) return;
    setError(null);
    setSavingKey(key);
    try {
      const updated = await notificationApi.updatePreferences({ [key]: nextValue });
      setPreferences(updated);
      toast("알림 수신 설정을 변경했습니다.");
      router.refresh();
    } catch (e: unknown) {
      const apiErr = e as Partial<ApiError> | undefined;
      setError(apiErr?.message ?? "알림 수신 설정을 저장하지 못했습니다.");
    } finally {
      setSavingKey(null);
    }
  }

  if (loading) return <Loading />;
  if (loadError || !preferences) {
    return (
      <ErrorState
        message={loadError ?? "알림 수신 설정을 불러오지 못했습니다."}
        retry={loadPreferences}
      />
    );
  }

  return (
    <Section title="알림 수신 설정">
      <div className="stack">
        <p className="muted">
          채점 결과(완료·실패)와 시스템 공지 알림은 필수 알림으로 항상 수신됩니다.
        </p>
        {error && (
          <p className="text-sm text-danger" role="alert">
            {error}
          </p>
        )}
        {NOTIFICATION_PREFERENCE_ITEMS.map((item) => {
          const enabled = preferences[item.key];
          const isSaving = savingKey === item.key;
          return (
            <div key={item.key} className="card row between">
              <div>
                <strong>{item.label}</strong>
                <p className="muted">{item.description}</p>
              </div>
              <div className="row">
                <Badge tone={enabled ? "ok" : ""}>{enabled ? "수신 중" : "꺼짐"}</Badge>
                <Button
                  type="button"
                  disabled={isSaving}
                  aria-pressed={enabled}
                  onClick={() => handleToggle(item.key, !enabled)}
                >
                  {enabled ? "끄기" : "켜기"}
                </Button>
              </div>
            </div>
          );
        })}
      </div>
    </Section>
  );
}
