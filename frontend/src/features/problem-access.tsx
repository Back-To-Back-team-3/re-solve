"use client";
import { useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { Button, ErrorState, Go, Loading } from "@/components/ui";
import { memberApi, studyApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import { problemAccess } from "@/services/problem-access";
import type { Problem } from "@/services/types";
import { useWorkspace } from "@/stores/workspace";

/** 회원이 바뀌면 이전 회원의 레벨 응답을 접근 판단에 사용하지 않는다. */
export function useProblemLevels() {
  const memberId = useWorkspace((state) => state.member?.memberId);
  const resource = useResource(async () => ({
    memberId,
    content: memberId ? (await memberApi.getTagLevels()).content : [],
  }), `problem-levels:${memberId}`);
  return { ...resource, memberId, loading: resource.loading || resource.data?.memberId !== memberId };
}

/** 일반 풀이 화면에만 적용한다. 시험·진단의 고정 문제 접근 규칙은 유지한다. */
export function ProblemAccessGate({ problem, children }: { problem: Problem; children: ReactNode }) {
  const levels = useProblemLevels();
  return <ProblemAccessContent key={`${levels.memberId}:${problem.id}`} problem={problem} levels={levels}>
    {children}
  </ProblemAccessContent>;
}

function ProblemAccessContent({ problem, levels, children }: {
  problem: Problem; levels: ReturnType<typeof useProblemLevels>; children: ReactNode;
}) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  if (levels.error) return <ErrorState message={`레벨을 확인하지 못했습니다. ${levels.error.message}`} retry={levels.retry} />;
  if (levels.loading) return <Loading />;
  if (!levels.memberId) return <Go href="/login">로그인 후 풀기</Go>;
  const access = problemAccess(problem, levels.data?.content ?? []);
  if (access.allowed) return children;

  async function enter(tagId: string) {
    setPending(true);
    setError("");
    try {
      await memberApi.enterTag(tagId);
      levels.retry();
      router.refresh();
    } catch (failure) {
      if (typeof failure === "object" && failure !== null && "code" in failure
        && failure.code === "LEVEL_ALREADY_ENTERED") {
        levels.retry();
      } else {
        setError(typeof failure === "object" && failure !== null && "message" in failure
          && typeof failure.message === "string" ? failure.message : "태그를 시작하지 못했습니다. 다시 시도해 주세요.");
      }
    } finally {
      setPending(false);
    }
  }

  return <AssignedProblemAccess problemId={problem.id} memberId={levels.memberId} fallback={<section className="card stack" aria-label="문제 레벨 제한">
    <h2>레벨 잠금</h2>
    <p>모든 알고리즘의 레벨이 Lv. {problem.difficulty} 이상이어야 풀 수 있어요.</p>
    {access.blockedTags.map((tag) => <div key={tag.tagId} className="stack-sm">
      <p>{tag.name}: {tag.unentered ? "미진입 (Lv.0)" : tag.level} · 필요 Lv. {problem.difficulty}</p>
      {tag.unentered && <Button disabled={pending} onClick={() => void enter(tag.tagId)}>
        {tag.name} Lv.1로 시작
      </Button>}
    </div>)}
    <p>처음 시작하는 태그는 진단 없이 Lv.1로 시작할 수 있어요. 이미 시작한 태그는 마이페이지의 스킬 탭에서 레벨을 확인해 주세요.</p>
    {error && <p className="error-text" role="alert">{error}</p>}
    <Go href="/mypage">진단 테스트로 레벨 올리기</Go>
    <Button disabled={pending} onClick={levels.retry}>레벨 다시 확인</Button>
  </section>}>{children}</AssignedProblemAccess>;
}

/** 개인 레벨이 부족할 때만 배정 여부를 확인하며, 실패하면 기존 잠금을 유지한다. */
function AssignedProblemAccess({ problemId, memberId, children, fallback }: {
  problemId: string; memberId: string; children: ReactNode; fallback: ReactNode;
}) {
  const assigned = useResource(() => studyApi.getAssignedProblem(problemId), `assigned-problem:${memberId}:${problemId}`);
  if (assigned.loading) return <Loading />;
  return !assigned.error && assigned.data?.problemId === problemId && assigned.data.assigned === true ? children : fallback;
}
