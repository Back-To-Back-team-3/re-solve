import { apiFetchStream } from "@/services/api";
import type { NotificationType, SSENotificationEvent } from "@/services/types";

const notificationTypes = [
  "SUBMISSION_JUDGED", "SUBMISSION_FAILED", "EXAM_STARTING_SOON", "EXAM_FINALIZED", "EXAM_UPDATED", "EXAM_CANCELED",
  "CONTEST_STARTED", "CONTEST_FINALIZED", "CONTEST_CANCELED", "STUDY_APPLICATION_DECIDED", "ASSIGNMENT_PUBLISHED",
  "ASSIGNMENT_DEADLINE_APPROACHING", "ASSIGNMENT_INCOMPLETE_REPEATED", "LEVEL_CHANGED", "HINT_READY", "GITHUB_SYNC_FAILED", "SYSTEM_NOTICE",
] satisfies NotificationType[];

/** API 명세서 §7.1.1의 외부 이벤트를 검증한 뒤 화면에 전달한다. */
function isNotification(value: unknown): value is SSENotificationEvent {
  if (typeof value !== "object" || value === null) return false;
  return "notificationId" in value && typeof value.notificationId === "string"
    && "type" in value && notificationTypes.some((type) => type === value.type)
    && "title" in value && typeof value.title === "string"
    && "content" in value && typeof value.content === "string"
    && "linkUrl" in value && typeof value.linkUrl === "string"
    && "createdAt" in value && typeof value.createdAt === "string";
}

/**
 * API 명세서 §7.1.1에 따라 수신 완료 ID로 재연결한다.
 * 갱신 후에도 인증이 거절되면 중단하며, 정리 함수는 요청·재연결을 취소한다.
 */
export function connectNotificationStream(onNotification: (event: SSENotificationEvent) => void): () => void {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let lastEventId = "";
  let retryMs = 3000;

  const connect = async () => {
    try {
      const response = await apiFetchStream("/api/v1/notifications/subscribe", controller.signal, lastEventId);
      if (response.status === 204) return;
      if (!response.headers.get("content-type")?.startsWith("text/event-stream") || !response.body)
        throw new Error("알림 스트림 응답이 아닙니다.");
      const reader = response.body.getReader();
      const cancel = () => { void reader.cancel().catch(() => {}); };
      controller.signal.addEventListener("abort", cancel, { once: true });
      const decoder = new TextDecoder();
      let buffer = "";
      let pendingId = lastEventId;
      let event = "";
      let data: string[] = [];
      // CRLF가 청크 경계에서 나뉘거나 CR만 사용하는 서버도 처리한다.
      let skipLf = false;
      const line = (value: string) => {
        if (!value) {
          if (event === "notification" && data.length && !controller.signal.aborted) {
            try {
              const payload: unknown = JSON.parse(data.join("\n"));
              if (isNotification(payload)) onNotification(payload);
            } catch { /* 잘못된 이벤트만 건너뛴다. */ }
          }
          // 미완성 프레임의 ID는 연결 종료 시 폐기해 재전송을 막지 않는다.
          lastEventId = pendingId;
          event = "";
          data = [];
          return;
        }
        const colon = value.indexOf(":");
        const field = colon < 0 ? value : value.slice(0, colon);
        const content = colon < 0 ? "" : value.slice(colon + 1).replace(/^ /, "");
        if (field === "event") event = content;
        if (field === "data") data.push(content);
        if (field === "id" && !content.includes("\0")) pendingId = content;
        if (field === "retry" && /^\d+$/.test(content)) retryMs = Math.min(30000, Math.max(1000, Number(content)));
      };
      try {
        while (!controller.signal.aborted) {
          const chunk = await reader.read();
          if (chunk.done) break;
          for (const character of decoder.decode(chunk.value, { stream: true })) {
            if (skipLf && character === "\n") { skipLf = false; continue; }
            skipLf = character === "\r";
            if (character === "\r" || character === "\n") { line(buffer); buffer = ""; }
            else buffer += character;
          }
        }
      } finally {
        controller.signal.removeEventListener("abort", cancel);
        await reader.cancel().catch(() => {});
        reader.releaseLock();
      }
    } catch (error) {
      // 갱신으로도 인증되지 않으면 반복 요청하지 않고 다음 로그인까지 멈춘다.
      if (typeof error === "object" && error !== null && "code" in error && error.code === "UNAUTHORIZED") return;
    }
    if (!controller.signal.aborted) timer = setTimeout(() => { void connect(); }, retryMs);
  };
  void connect();
  return () => { clearTimeout(timer); controller.abort(); };
}
