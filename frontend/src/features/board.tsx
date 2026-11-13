"use client";
import { Construction } from "lucide-react";

export function Board({ scope: _scope }: { scope: string; title?: string }) {
  return (
    <div className="stack items-center py-12 text-center">
      <Construction size={40} className="text-muted" />
      <h3>커뮤니티 게시판 준비 중</h3>
      <p className="muted">스터디별 게시판은 스터디 화면에서 이용할 수 있습니다.</p>
    </div>
  );
}

export function Note({ problemId: _problemId }: { problemId: string }) {
  return (
    <div className="stack items-center py-12 text-center">
      <Construction size={40} className="text-muted" />
      <h3>노트 기능 준비 중</h3>
    </div>
  );
}
