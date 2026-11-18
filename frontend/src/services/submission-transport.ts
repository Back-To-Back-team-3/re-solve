/** 서버 채점 기한과 별개로 제출 접수 응답을 기다리는 클라이언트 제한 시간이다. */
export const submissionResponseTimeoutMs = 30_000;
const activeRequests = new Set<AbortController>();

/** 로그아웃한 세션의 제출과 인증 갱신·재전송을 함께 취소한다. */
export function cancelSubmissionRequests(): void {
  for (const controller of activeRequests) controller.abort();
  activeRequests.clear();
}

/** 제출의 인증 갱신·본문 수신까지 같은 취소 신호와 응답 대기 기한을 적용한다. */
export async function withSubmissionTransport<T>(send: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  activeRequests.add(controller);
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, submissionResponseTimeoutMs);
  try {
    const result = await send(controller.signal);
    controller.signal.throwIfAborted();
    return result;
  } catch (failure) {
    if (timedOut) throw new Error("제출 접수 여부를 확인하지 못했습니다. 다시 시도해 주세요.");
    throw failure;
  } finally {
    clearTimeout(timer);
    activeRequests.delete(controller);
  }
}
