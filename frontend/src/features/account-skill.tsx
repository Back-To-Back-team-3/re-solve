"use client";
import { memberApi, problemTagApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import {
  Button,
  Empty,
  ErrorState,
  Loading,
  Section,
  useAction,
} from "@/components/ui";

export function SkillPage() {
  const tagLevelsR = useResource(memberApi.getTagLevels, "tag-levels");
  const tagsR = useResource(problemTagApi.getTags, "problem-tags");
  const action = useAction();

  if (tagLevelsR.loading || tagsR.loading) return <Loading />;
  if (tagLevelsR.error)
    return <ErrorState message={tagLevelsR.error.message} retry={tagLevelsR.retry} />;
  if (tagsR.error)
    return <ErrorState message={tagsR.error.message} retry={tagsR.retry} />;

  const levels = tagLevelsR.data?.content ?? [];
  const allTags = tagsR.data?.content ?? [];

  function startDiagnose(tagId: string) {
    action(
      async () => {
        await memberApi.startDiagnosis(tagId);
        tagLevelsR.retry();
      },
      "진단 세션이 시작됐습니다.",
    );
  }

  return (
    <Section title="태그별 진단 레벨">
      <div className="stack">
        {allTags.length === 0 && <Empty title="태그 정보를 불러올 수 없습니다." />}
        {allTags.map((tag) => {
          const tl = levels.find((l) => l.tagId === tag.tagId);
          const inProgress = tl?.diagnosis.inProgressDiagnosisId != null;
          const cooldown = tl?.diagnosis.cooldownEndsAt;
          const cooldownDays = cooldown
            ? Math.ceil(
                (new Date(cooldown).getTime() - new Date().getTime()) / 86400000,
              )
            : 0;
          return (
            <div key={tag.tagId} className="card row">
              <span>{tag.name}</span>
              {!tl && (
                <Button onClick={() => startDiagnose(tag.tagId)}>진단 시작</Button>
              )}
              {tl && inProgress && (
                <Button disabled title="진단이 진행 중입니다">
                  이어서 풀기
                </Button>
              )}
              {tl && !inProgress && (
                <div className="row">
                  <span className="badge">{tl.level}</span>
                  {tl.achievements[0] && (
                    <span className="muted">
                      {tl.achievements[0].achievementRate.toFixed(0)}%
                    </span>
                  )}
                  <Button
                    disabled={!!cooldown && cooldownDays > 0}
                    title={
                      cooldown && cooldownDays > 0 ? `D-${cooldownDays}` : undefined
                    }
                    onClick={() => startDiagnose(tag.tagId)}
                  >
                    {cooldown && cooldownDays > 0 ? `D-${cooldownDays}` : "재응시"}
                  </Button>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </Section>
  );
}
