import { Construction } from "lucide-react";

/** 공개 API가 없는 기능을 탭은 남긴 채 준비 중으로 표시한다. */
export function ComingSoon({ title }: { title: string }) {
  return (
    <div className="stack items-center py-12 text-center">
      <Construction size={40} className="text-muted" />
      <h3>{title} 준비 중</h3>
    </div>
  );
}

export function Board() {
  return <ComingSoon title="커뮤니티 게시판" />;
}

// 풀이 노트는 API 명세에 없어 브라우저에만 저장되던 목업 기능이므로 준비 중으로 둔다.
export function Note() {
  return <ComingSoon title="풀이 노트" />;
}
