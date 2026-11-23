"use client";
import { useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { actionErrorMessage, studyApi, studyPostApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { PostDetail, StudyDetail } from "@/services/types";
import { Badge, Button, Empty, ErrorState, Go, Heading, Loading, Section, useAction } from "@/components/ui";
import { useWorkspace } from "@/stores/workspace";
import {
  POST_CONTENT_MAX,
  POST_TITLE_MAX,
  canEditPost,
  canModeratePost,
  canReadPosts,
  postInputError,
} from "@/features/study-post-policy";
import { postTime } from "./study-posts";

/** 게시글 상세. 숨김·삭제·다른 스터디 글은 서버가 STUDY_POST_NOT_FOUND로 돌려준다(api-spec §5.5.3). */
export function StudyPostDetail({ studyId, postId }: { studyId: string; postId: string }) {
  const [version, setVersion] = useState(0);
  const study = useResource(() => studyApi.getDetail(studyId), `${studyId}:post-study`);
  const post = useResource(() => studyPostApi.get(studyId, postId), `${studyId}:${postId}:${version}`);

  if (study.loading || post.loading)
    return <div className="container"><Loading /></div>;
  if (study.error)
    return <div className="container"><ErrorState message={study.error.message} retry={study.retry} /></div>;
  const back = <Go className="small" href={`/studies/${studyId}?tab=board`}>← 게시판으로 돌아가기</Go>;
  // 구성원이 아니면 서버가 게시글 조회를 403으로 거절하므로, 게시글 오류보다 먼저 가입 여부로 안내한다.
  if (!canReadPosts(study.data!))
    return (
      <div className="container stack">
        {back}
        <Empty title="구성원만 볼 수 있어요" description="스터디에 가입한 뒤 게시글을 볼 수 있습니다." />
      </div>
    );
  // 공용 NotFound는 "{what}를"로 조사를 붙여 "게시글"에 맞지 않으므로 같은 모양의 안내를 직접 쓴다.
  if (post.error?.code === "STUDY_POST_NOT_FOUND")
    return (
      <div className="container stack">
        {back}
        <Empty title="게시글을 찾을 수 없어요" description="삭제되었거나 숨겨진 글입니다." />
      </div>
    );
  if (post.error)
    return <div className="container"><ErrorState message={post.error.message} retry={post.retry} /></div>;

  return (
    <div className="container stack">
      {back}
      <PostView study={study.data!} post={post.data!} onChanged={() => setVersion((v) => v + 1)} />
    </div>
  );
}

function PostView({ study, post, onChanged }: { study: StudyDetail; post: PostDetail; onChanged: () => void }) {
  const router = useRouter();
  const action = useAction();
  const memberId = useWorkspace((s) => s.member?.memberId);
  const [editing, setEditing] = useState(false);
  const backToBoard = () => { router.push(`/studies/${study.studyId}?tab=board`); router.refresh(); };

  async function remove() {
    if (!window.confirm("게시글을 삭제하시겠습니까?")) return;
    const done = await action(() => studyPostApi.remove(study.studyId, post.postId), "게시글을 삭제했습니다.");
    if (done) backToBoard();
  }

  async function moderate(next: { isPinned: boolean; isNotice: boolean; isHidden: boolean }, success: string) {
    const done = await action(() => studyPostApi.moderate(study.studyId, post.postId, next), success);
    if (!done) return;
    // 숨긴 글은 이후 상세 조회가 404이므로 목록으로 돌아간다.
    if (next.isHidden) backToBoard();
    else onChanged();
  }

  // 운영진 관리는 세 값을 모두 보내야 하므로(api-spec §5.5.5) 화면에 보이는 현재 값을 바탕으로 하나만 바꾼다.
  const current = { isPinned: post.isPinned, isNotice: post.isNotice, isHidden: false };

  async function hide() {
    if (!window.confirm("이 글을 숨기시겠습니까? 구성원에게 보이지 않습니다.")) return;
    await moderate({ ...current, isHidden: true }, "게시글을 숨겼습니다.");
  }

  return (
    <>
      <Heading
        title={post.title}
        eyebrow="STUDY BOARD"
        description={`${post.author.nickname ?? "알 수 없음"} · ${postTime(post.createdAt)}`}
        action={
          <div className="row">
            {post.isPinned && <Badge tone="accent">고정</Badge>}
            {post.isNotice && <Badge tone="success">공지</Badge>}
          </div>
        }
      />
      {editing ? (
        <PostEditForm
          study={study}
          post={post}
          onCancel={() => setEditing(false)}
          onSaved={() => { setEditing(false); onChanged(); }}
        />
      ) : (
        <section className="card stack">
          <p className="whitespace-pre-wrap">{post.content}</p>
          {canEditPost(study, post.author.memberId, memberId) && (
            <div className="row">
              <Button onClick={() => setEditing(true)}>수정</Button>
              <Button onClick={remove}>삭제</Button>
            </div>
          )}
        </section>
      )}
      {canModeratePost(study) && (
        <Section title="운영진 관리">
          <div className="row">
            <Button onClick={() => moderate({ ...current, isPinned: !post.isPinned }, post.isPinned ? "고정을 해제했습니다." : "상단에 고정했습니다.")}>
              {post.isPinned ? "고정 해제" : "상단 고정"}
            </Button>
            <Button onClick={() => moderate({ ...current, isNotice: !post.isNotice }, post.isNotice ? "공지를 해제했습니다." : "공지로 지정했습니다.")}>
              {post.isNotice ? "공지 해제" : "공지 지정"}
            </Button>
            <Button onClick={hide}>숨기기</Button>
          </div>
        </Section>
      )}
      <Section title={`댓글 ${post.comments.length}`}>
        {post.comments.length === 0 ? (
          <p className="muted">아직 댓글이 없어요.</p>
        ) : (
          <ul className="stack">
            {post.comments.map((comment) => (
              <li key={comment.commentId} className="inset stack-sm">
                <p className="muted">{comment.author.nickname ?? "알 수 없음"} · {postTime(comment.createdAt)}</p>
                <p className="whitespace-pre-wrap">{comment.content}</p>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </>
  );
}

function PostEditForm({ study, post, onCancel, onSaved }: {
  study: StudyDetail;
  post: PostDetail;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;
    const values = new FormData(event.currentTarget);
    const title = String(values.get("title") ?? "");
    const content = String(values.get("content") ?? "");
    const invalid = postInputError(title, content);
    if (invalid) { setError(invalid); return; }
    inFlight.current = true;
    setBusy(true); setError("");
    try {
      await studyPostApi.update(study.studyId, post.postId, { title: title.trim(), content });
      onSaved();
    } catch (failure) {
      setError(actionErrorMessage(failure));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <form className="card stack" onSubmit={submit}>
      {error && <p role="alert" className="error-text">{error}</p>}
      <fieldset className="stack" disabled={busy}>
        <label className="field">제목<input className="input" name="title" required maxLength={POST_TITLE_MAX} defaultValue={post.title} /></label>
        <label className="field">본문<textarea className="input" name="content" required maxLength={POST_CONTENT_MAX} rows={10} defaultValue={post.content} /></label>
        <div className="row">
          <Button type="submit" className="primary">{busy ? "저장 중…" : "저장"}</Button>
          <Button type="button" onClick={onCancel}>취소</Button>
        </div>
      </fieldset>
    </form>
  );
}
