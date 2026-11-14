/** 최초 요청부터 24시간 이내에만 동일 접수를 보장한다. (API 명세서 §3.1.2) */
export const practiceRequestTtlMs = 24 * 60 * 60 * 1000;
export const expiredPracticeRequestMessage = "최초 요청 후 24시간이 지나 같은 제출의 재요청을 보장할 수 없습니다. 기존 접수 여부를 확인한 뒤 현재 코드로 새 제출을 선택해 주세요.";

/** 정확히 24시간이 지난 요청부터 재전송을 제한한다. (API 명세서 §3.1.2) */
export function isPracticeRequestExpired(createdAt: number, now: number): boolean {
  return now - createdAt >= practiceRequestTtlMs;
}
