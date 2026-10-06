"use client";
import { useState } from "react";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import {
  Button,
  Empty,
  ErrorState,
  Loading,
  date,
  useAction,
} from "@/components/ui";
export function Board({
  scope,
  title = "새 글 작성",
}: {
  scope: string;
  title?: string;
}) {
  const r = useResource(() => api.messages(scope), scope),
    action = useAction();
  const [body, setBody] = useState(""),
    [reply, setReply] = useState(""),
    [replyTo, setReplyTo] = useState("");
  return (
    <div className="stack">
      <form
        className="stack-sm"
        onSubmit={async (e) => {
          e.preventDefault();
          if (await action(() => api.post(scope, body), "글을 등록했습니다."))
            setBody("");
        }}
      >
        <label className="field">
          {title}
          <textarea
            className="input"
            required
            maxLength={3000}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="궁금한 점이나 오늘 배운 내용을 함께 나눠요."
          />
        </label>
        <div>
          <Button className="primary">글 등록</Button>
        </div>
      </form>
      {r.loading ? (
        <Loading />
      ) : r.error ? (
        <ErrorState message={r.error} retry={r.retry} />
      ) : !r.data?.length ? (
        <Empty title="첫 이야기를 남겨 주세요" />
      ) : (
        r.data
          .filter((m) => !m.parentId)
          .map((m) => (
            <article key={m.id} className="post stack-sm">
              <div className="row between">
                <b>{m.author}</b>
                <small>{date(m.createdAt)}</small>
              </div>
              <p className="message-body">{m.body}</p>
              {r
                .data!.filter((x) => x.parentId === m.id)
                .map((x) => (
                  <div className="reply" key={x.id}>
                    <b>{x.author}</b>
                    <p className="message-body">{x.body}</p>
                  </div>
                ))}
              <div>
                <Button
                  className="small"
                  onClick={() => {
                    setReplyTo(replyTo === m.id ? "" : m.id);
                    setReply("");
                  }}
                >
                  댓글 쓰기
                </Button>
              </div>
              {replyTo === m.id && (
                <form
                  className="row"
                  onSubmit={async (e) => {
                    e.preventDefault();
                    if (
                      await action(
                        () => api.post(scope, reply, m.id),
                        "댓글을 등록했습니다.",
                      )
                    ) {
                      setReply("");
                      setReplyTo("");
                    }
                  }}
                >
                  <input
                    className="input"
                    aria-label="댓글"
                    required
                    value={reply}
                    maxLength={1000}
                    onChange={(e) => setReply(e.target.value)}
                  />
                  <Button>등록</Button>
                </form>
              )}
            </article>
          ))
      )}
    </div>
  );
}
export function Note({ problemId }: { problemId: string }) {
  const r = useResource(() => api.note(problemId), problemId),
    action = useAction();
  const [draft, setDraft] = useState<string | null>(null);
  return (
    <form
      className="stack-sm"
      onSubmit={(e) => {
        e.preventDefault();
        action(
          () => api.saveNote(problemId, draft ?? r.data ?? ""),
          "풀이 노트를 저장했습니다.",
        );
      }}
    >
      <label className="field">
        나만의 풀이 노트
        <textarea
          className="input"
          value={draft ?? r.data ?? ""}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="접근 방법과 놓친 조건을 기록해 보세요."
        />
      </label>
      <small>노트는 현재 사용자에게만 표시됩니다.</small>
      <div>
        <Button className="primary">노트 저장</Button>
      </div>
    </form>
  );
}
