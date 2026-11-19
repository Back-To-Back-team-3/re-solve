import type { ExamRunResponse, Problem } from "@/services/types";

/** 시험 Run의 공개 결과만 표시하며 통과 판정이나 점수를 만들지 않는다. */
export function ExamRunResult({ result, samples }: { result: ExamRunResponse; samples: Problem["samples"] }) {
  return <div className="stack-sm" aria-label="시험 Run 실행 결과">
    {result.results.map((item) => <article className="card stack-sm min-w-0" key={`${item.type}:${item.index}`}>
      <h4>{item.type === "SAMPLE" ? "공개 예제" : "직접 입력"} {item.index + 1}</h4>
      {item.type === "SAMPLE" && <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">기대값: {samples[item.index]?.expected ?? "—"}</pre>}
      <pre className="mono whitespace-pre-wrap [overflow-wrap:anywhere]">실제 반환값: {item.output ?? "—"}</pre>
      {item.error && <pre role="alert" className="error-text whitespace-pre-wrap [overflow-wrap:anywhere]">{item.error}</pre>}
      <p>실행 시간: {item.timeMs}ms · 메모리: {item.memoryKb}KiB</p>
    </article>)}
  </div>;
}
