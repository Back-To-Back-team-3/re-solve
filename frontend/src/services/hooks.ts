"use client";
import { useEffect, useState, useSyncExternalStore } from "react";
import { getRevision, subscribe } from "./mock-api";
export function useResource<T>(load: () => Promise<T>, key = "") {
  const revision = useSyncExternalStore(subscribe, getRevision, () => 0);
  const [result, setResult] = useState<{
    key: string;
    data: T | undefined;
    loading: boolean;
    error: string;
  }>({ key, data: undefined, loading: true, error: "" });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let alive = true;
    setResult((r) => ({
      key,
      data: r.key === key ? r.data : undefined,
      loading: r.key !== key || r.data === undefined,
      error: "",
    }));
    load()
      .then((data) => {
        if (alive) setResult({ key, data, loading: false, error: "" });
      })
      .catch((e) => {
        if (alive)
          setResult({
            key,
            data: undefined,
            loading: false,
            error: e.message || "불러오지 못했습니다.",
          });
      });
    return () => {
      alive = false;
    };
    // The explicit key describes the service arguments; service closures are recreated by rendering.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, revision, retry]);
  return {
    ...result,
    data: result.key === key ? result.data : undefined,
    loading: result.key !== key || result.loading,
    retry: () => setRetry((x) => x + 1),
  };
}
