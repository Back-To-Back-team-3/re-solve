"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { diagnosisApi, isApiError, memberApi, problemTagApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { Button, Empty, ErrorState, Go, Loading, Section, useAction } from "@/components/ui";

export function SkillPage({ selectedTag }: { selectedTag?: string }) {
  const tagLevelsR = useResource(memberApi.getTagLevels, "tag-levels");
  const tagsR = useResource(problemTagApi.getTags, "problem-tags");
  const action = useAction();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [unavailableTag, setUnavailableTag] = useState("");
  const lock = useRef(false);
  const [now, setNow] = useState(Date.now);
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 60000); return () => clearInterval(timer); }, []);

  async function startDiagnose(tagId: string) {
    if (lock.current) return;
    lock.current = true; setBusy(true); setUnavailableTag("");
    try {
      await action(async () => {
        // 시작 응답이 유실돼도 활성 진단부터 확인해 중복 응시를 만들지 않는다. (API 명세서 §1.5.1)
        const levels = await memberApi.getTagLevels();
        let id = levels.content.find((level) => level.tagId === tagId)?.diagnosis.inProgressDiagnosisId;
        if (!id) {
          try { id = (await diagnosisApi.start(tagId)).diagnosisId; }
          catch (failure) {
            if (isApiError(failure) && failure.code === "DIAGNOSIS_PROBLEMS_UNAVAILABLE") setUnavailableTag(tagId);
            const recoverable = !isApiError(failure) || ["UNKNOWN", "COMMON_DEPENDENCY_UNAVAILABLE", "DIAGNOSIS_ALREADY_IN_PROGRESS"].includes(failure.code);
            if (!recoverable) throw failure;
            try {
              const refreshed = await memberApi.getTagLevels();
              id = refreshed.content.find((level) => level.tagId === tagId)?.diagnosis.inProgressDiagnosisId;
            } catch { /* 복구 조회 실패로 최초 시작 오류를 숨기지 않는다. */ }
            if (!id) throw failure;
          }
        }
        router.push(`/diagnoses/${id}`); router.refresh();
      });
    } finally { lock.current = false; setBusy(false); }
  }

  if (tagLevelsR.loading || tagsR.loading) return <Loading />;
  if (tagLevelsR.error) return <ErrorState message={tagLevelsR.error.message} retry={tagLevelsR.retry} />;
  if (tagsR.error) return <ErrorState message={tagsR.error.message} retry={tagsR.retry} />;
  const levels = tagLevelsR.data?.content ?? [];
  const allTags = tagsR.data?.content ?? [];
  return <Section title="태그별 진단 레벨">
    <div className="stack">
      {allTags.length === 0 && <Empty title="태그 정보를 불러올 수 없습니다." />}
      {allTags.map((tag) => {
        const level = levels.find((item) => item.tagId === tag.tagId);
        const inProgress = level?.diagnosis.inProgressDiagnosisId;
        const cooldown = level?.diagnosis.cooldownEndsAt;
        const cooldownDays = cooldown ? Math.max(0, Math.ceil((Date.parse(cooldown) - now) / 86400000)) : 0;
        const eligible = !level || level.diagnosis.isEligible;
        return <div key={tag.tagId} className={`card row wrap ${selectedTag === tag.tagId ? "border-primary" : ""}`}>
          <span>{tag.name}</span>
          {level && <span className="badge">{level.level}</span>}
          {level?.achievements[0] && <span className="muted">{level.achievements[0].achievementRate.toFixed(0)}%</span>}
          {inProgress ? <Go href={`/diagnoses/${inProgress}`}>이어서 풀기</Go> : <Button disabled={busy || !eligible || cooldownDays > 0}
            title={!eligible && !cooldownDays ? "현재 레벨은 진단 응시 대상이 아닙니다." : undefined}
            onClick={() => void startDiagnose(tag.tagId)}>
            {cooldownDays ? `D-${cooldownDays}` : level && level.level !== "LV0" ? "재응시" : "진단 시작"}
          </Button>}
        </div>;
      })}
      {unavailableTag && <p role="alert">출제 가능한 문제가 부족합니다. <Go href={`/problems?tagIds=${unavailableTag}`}>문제 목록으로 이동</Go></p>}
      <Go href="/diagnoses">진단 응시·결과 이력</Go>
    </div>
  </Section>;
}
