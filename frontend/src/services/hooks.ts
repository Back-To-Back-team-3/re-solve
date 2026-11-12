"use client";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ApiError } from "./api";

export function useResource<T>(
  load: () => Promise<T>,
  key = "",
): { data: T | undefined; loading: boolean; error: ApiError | null; retry: () => void } {
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    load()
      .then((d) => {
        if (!cancelled) { setData(d); setLoading(false); }
      })
      .catch((e: ApiError) => {
        if (!cancelled) { setError(e); setLoading(false); }
      });
    return () => { cancelled = true; };
    // load는 렌더링마다 재생성되므로 key와 retryCount만 deps로 사용한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, retryCount]);

  const retry = useCallback(() => setRetryCount((n) => n + 1), []);
  return { data, loading, error, retry };
}

export function usePollUntil<T>(
  loadFn: () => Promise<T>,
  isDone: (data: T) => boolean,
  options?: { intervalMs?: number; timeoutMs?: number },
): { data: T | undefined; loading: boolean; error: ApiError | null; polling: boolean } {
  const { intervalMs = 1500, timeoutMs = 30000 } = options ?? {};
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [polling, setPolling] = useState(false);
  const startedAt = useRef(0);

  useEffect(() => {
    startedAt.current = Date.now();
    let cancelled = false;
    const tick = async () => {
      try {
        const result = await loadFn();
        if (cancelled) return;
        setData(result);
        setLoading(false);
        if (isDone(result)) { setPolling(false); return; }
        if (Date.now() - startedAt.current >= timeoutMs) {
          setError({ code: "TIMEOUT", message: "채점 시간이 초과됐습니다" });
          setPolling(false);
          return;
        }
        setPolling(true);
        setTimeout(tick, intervalMs);
      } catch (e) {
        if (!cancelled) { setError(e as ApiError); setLoading(false); setPolling(false); }
      }
    };
    tick();
    return () => { cancelled = true; };
    // loadFn과 isDone은 렌더링마다 재생성될 수 있으므로 의도적으로 초기값만 사용한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { data, loading, error, polling };
}

export function useSSE(
  url: string | null,
  options?: {
    onMessage: (event: MessageEvent) => void;
    onError?: () => void;
    enabled?: boolean;
  },
): { connected: boolean; lastEventId: string | null } {
  const [connected, setConnected] = useState(false);
  const [lastEventId, setLastEventId] = useState<string | null>(null);
  const onMessageRef = useRef(options?.onMessage);
  const onErrorRef = useRef(options?.onError);

  // 매 렌더 후 최신 콜백을 ref에 반영해 스테일 클로저를 방지한다. useLayoutEffect는 렌더 직후 동기 실행되므로 EventSource 핸들러보다 먼저 반영된다.
  useLayoutEffect(() => {
    onMessageRef.current = options?.onMessage;
    onErrorRef.current = options?.onError;
  });

  useEffect(() => {
    if (!url || options?.enabled === false) return;

    const es = new EventSource(url, { withCredentials: true });
    es.onopen = () => setConnected(true);
    es.onmessage = (e) => {
      setLastEventId(e.lastEventId || null);
      onMessageRef.current?.(e);
    };
    es.onerror = () => {
      setConnected(false);
      onErrorRef.current?.();
    };

    return () => {
      es.close();
      setConnected(false);
    };
    // options 객체는 렌더링마다 재생성되므로 url과 enabled만 deps로 사용한다. 콜백은 ref로 최신값을 참조한다.
  }, [url, options?.enabled]);

  return { connected, lastEventId };
}

export function useExamRankingSSE(
  examId: string,
  enabled: boolean,
  onUpdate: () => void,
): void {
  const onUpdateRef = useRef(onUpdate);
  useLayoutEffect(() => {
    onUpdateRef.current = onUpdate;
  });

  useEffect(() => {
    if (!enabled) return;
    const es = new EventSource(`/api/v1/exams/${examId}/events`, {
      withCredentials: true,
    });
    es.addEventListener("exam-result-updated", () => {
      onUpdateRef.current();
    });
    return () => es.close();
  }, [examId, enabled]);
}

export function useNotificationSSE(
  onNotification: (event: import("./types").SSENotificationEvent) => void,
): void {
  const onNotificationRef = useRef(onNotification);
  useLayoutEffect(() => {
    onNotificationRef.current = onNotification;
  });

  useEffect(() => {
    const es = new EventSource("/api/v1/notifications/subscribe", {
      withCredentials: true,
    });
    es.addEventListener("notification", (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data) as import("./types").SSENotificationEvent;
        onNotificationRef.current(data);
      } catch {
        // 파싱 실패 시 무시
      }
    });
    // heartbeat 이벤트는 연결 유지 목적이므로 무시한다.
    es.addEventListener("heartbeat", () => {});
    return () => es.close();
  }, []);
}
