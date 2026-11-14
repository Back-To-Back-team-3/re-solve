"use client";
import { useState, useEffect, useRef } from "react";
import { contestApi } from "@/services/api";
import type { ApiError } from "@/services/api";
import type { RankingItem, RankingResponse, ResultStatus } from "@/services/types";
import { Badge, Button, ErrorState, Loading } from "@/components/ui";
import { useExamRankingSSE } from "@/services/hooks";

export function ExamRanking({ id }: { id: string }) {
  const [items, setItems] = useState<RankingItem[]>([]);
  const [status, setStatus] = useState<ResultStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  async function fetchRanking(
    after?: string,
    retried = false,
  ): Promise<RankingResponse | null> {
    try {
      const data = await contestApi.getRanking(id, after);
      if (after) {
        setItems((prev) => [...prev, ...data.items]);
      } else {
        setItems(data.items);
      }
      setStatus(data.resultStatus);
      setHasMore(data.hasMore);
      setNextCursor(data.nextCursor);
      return data;
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "RESULT_CURSOR_EXPIRED" && !retried) {
        setNextCursor(null);
        return fetchRanking(undefined, true);
      }
      setError(err.message);
      return null;
    }
  }

  function schedulePolling() {
    clearTimeout(pollTimer.current);
    pollTimer.current = setTimeout(async () => {
      const data = await fetchRanking();
      if (data?.resultStatus === "HIDDEN") schedulePolling();
    }, 30000);
  }

  useEffect(() => {
    fetchRanking().then((data) => {
      setLoading(false);
      if (data?.resultStatus === "HIDDEN") schedulePolling();
    });
    return () => clearTimeout(pollTimer.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useExamRankingSSE(id, status === "PROVISIONAL", () => {
    clearTimeout(pollTimer.current);
    fetchRanking();
  });

  async function loadMore() {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const data = await contestApi.getRanking(id, nextCursor);
      setItems((prev) => [...prev, ...data.items]);
      setHasMore(data.hasMore);
      setNextCursor(data.nextCursor);
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "RESULT_CURSOR_EXPIRED") {
        setNextCursor(null);
        await fetchRanking();
      }
    } finally {
      setLoadingMore(false);
    }
  }

  if (loading) return <Loading />;
  if (error)
    return (
      <ErrorState
        message={error}
        retry={() => {
          setError(null);
          setLoading(true);
          fetchRanking().then(() => setLoading(false));
        }}
      />
    );

  return (
    <div className="stack">
      <div className="row between">
        <h3>전체 순위</h3>
        {status === "HIDDEN" && <Badge tone="pending">채점 집계 중</Badge>}
        {status === "PROVISIONAL" && <Badge tone="warning">잠정 결과</Badge>}
        {status === "FINALIZED" && <Badge tone="success">최종 확정</Badge>}
      </div>
      {status === "HIDDEN" ? (
        <p className="muted">
          채점 결과를 집계 중입니다. 최대 30초마다 자동으로 갱신됩니다.
        </p>
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>순위</th>
                  <th>참가자</th>
                  <th>점수</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.memberId}>
                    <td>{item.pending ? "—" : (item.rank ?? "—")}</td>
                    <td>{item.nickname}</td>
                    <td>
                      {item.pending ? (
                        <Badge tone="pending">채점 중</Badge>
                      ) : (
                        (item.score ?? "—")
                      )}
                    </td>
                  </tr>
                ))}
                {items.length === 0 && (
                  <tr>
                    <td colSpan={3} className="muted">
                      아직 순위 데이터가 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {hasMore && (
            <Button className="small" disabled={loadingMore} onClick={loadMore}>
              {loadingMore ? "로딩 중…" : "더 보기"}
            </Button>
          )}
        </>
      )}
    </div>
  );
}
