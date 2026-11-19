/** 배정 확인이 기존 레벨 진입 동작을 무기한 가리지 않도록 하는 클라이언트 기한이다. */
export const assignedProblemTimeoutMs = 10_000;
const activeSessions = new Set<AbortController>();

/** 로그아웃 시 배정 조회에서 시작한 이전 세션의 인증 갱신도 취소한다. */
export function cancelAssignedProblemRequests(): void {
  for (const session of activeSessions) session.abort();
  activeSessions.clear();
}

/** 화면 이탈·기한 만료는 조회만 취소하고, 이미 시작한 인증 갱신은 로그아웃 전까지 완료한다. */
export async function withAssignedProblemTransport<T>(signal: AbortSignal, send: (query: AbortSignal, refresh: AbortSignal) => Promise<T>): Promise<T> {
  const session = new AbortController();
  activeSessions.add(session);
  try {
    const query = AbortSignal.any([signal, session.signal]);
    query.throwIfAborted();
    const result = await send(query, session.signal);
    query.throwIfAborted();
    return result;
  } finally {
    activeSessions.delete(session);
  }
}
