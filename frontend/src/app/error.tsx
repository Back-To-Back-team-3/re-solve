"use client";
import { ErrorState } from "@/components/ui";
export default function Error({ reset }: { reset: () => void }) {
  return (
    <div className="container">
      <ErrorState message="화면을 표시하지 못했습니다." retry={reset} />
    </div>
  );
}
