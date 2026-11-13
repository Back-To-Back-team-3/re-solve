"use client";
import { useState } from "react";
import { Construction } from "lucide-react";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import { Button, useAction } from "@/components/ui";

export function Board({ scope: _scope }: { scope: string; title?: string }) {
  return (
    <div className="stack items-center py-12 text-center">
      <Construction size={40} className="text-muted" />
      <h3>커뮤니티 게시판 준비 중</h3>
    </div>
  );
}

export function Note({ problemId }: { problemId: string }) {
  const r = useResource(() => api.note(problemId), problemId),
    action = useAction();
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <form
      className="stack-sm"
      onSubmit={(e) => {
        e.preventDefault();
        action(
          () => api.saveNote(problemId, draft ?? r.data ?? ""),
          "풀이 노트를 저장했습니다.",
        );
      }}
    >
      <label className="field">
        나만의 풀이 노트
        <textarea
          className="input"
          value={draft ?? r.data ?? ""}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="접근 방법과 놓친 조건을 기록해 보세요."
        />
      </label>
      <small>노트는 현재 사용자에게만 표시됩니다.</small>
      <div>
        <Button className="primary">노트 저장</Button>
      </div>
    </form>
  );
}
