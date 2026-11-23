"use client";
import { useState } from "react";
import { MessageSquare } from "lucide-react";
import { studyPostApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { PostSummary, StudyDetail } from "@/services/types";
import { Badge, Button, Empty, ErrorState, Go, Loading, useAction } from "@/components/ui";
import { canModeratePost, canWritePost } from "@/features/study-post-policy";
import { Pager } from "./problem-list";

/** 목록·상세에 쓰는 작성 시각 표기. 서버 값은 +09:00 ISO 문자열이라 날짜·분까지만 자른다. */
export function postTime(value: string): string {
  return value.slice(0, 16).replace("T", " ");
}

/** `onRestore`가 있으면 숨긴 글 행이다. 숨긴 글은 상세가 404이므로 제목을 링크로 두지 않고 숨김 해제 버튼을 둔다. */
function PostRow({ studyId, post, onRestore }: { studyId: string; post: PostSummary; onRestore?: () => void }) {
  return (
    <li className="card stack-sm">
      <div className="row">
        {post.isPinned && <Badge tone="accent">고정</Badge>}
        {post.isNotice && <Badge tone="success">공지</Badge>}
        {onRestore ? (
          <b>{post.title}</b>
        ) : (
          <Go href={`/studies/${studyId}/posts/${post.postId}`}>
            <b>{post.title}</b>
          </Go>
        )}
        {onRestore && <Button onClick={onRestore}>숨김 해제</Button>}
      </div>
      <p className="muted row">
        <span>{post.authorNickname ?? "알 수 없음"}</span>
        <span>{postTime(post.createdAt)}</span>
        <span className="row" aria-label={`댓글 ${post.commentCount}개`}>
          <MessageSquare size={14} />
          {post.commentCount}
        </span>
      </p>
    </li>
  );
}

/** 스터디 상세의 게시판 탭. 고정 글이 먼저 오는 순서는 서버가 정한다(api-spec §5.5.1). */
export function PostsTab({ study, version }: { study: StudyDetail; version: number }) {
  const action = useAction();
  const [noticeOnly, setNoticeOnly] = useState(false);
  const [hiddenOnly, setHiddenOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [restored, setRestored] = useState(0);
  const posts = useResource(
    () => studyPostApi.list(study.studyId, { isNotice: noticeOnly, isHidden: hiddenOnly, page, size: 20 }),
    `${study.studyId}:posts:${noticeOnly}:${hiddenOnly}:${page}:${version}:${restored}`,
  );

  function toggleNotice(next: boolean) {
    setNoticeOnly(next);
    setPage(0);
  }

  function toggleHidden(next: boolean) {
    setHiddenOnly(next);
    setPage(0);
  }

  // 운영진 관리는 세 값을 모두 보내야 하므로(api-spec §5.5.5) 목록 항목의 고정·공지 값을 그대로 두고 숨김만 푼다.
  async function restore(post: PostSummary) {
    const done = await action(
      () => studyPostApi.moderate(study.studyId, post.postId, { isPinned: post.isPinned, isNotice: post.isNotice, isHidden: false }),
      "숨김을 해제했습니다.",
    );
    if (done) setRestored((n) => n + 1);
  }

  return (
    <div className="stack">
      <div className="row between">
        <label className="row">
          <input type="checkbox" checked={noticeOnly} onChange={(event) => toggleNotice(event.target.checked)} />
          공지만 보기
        </label>
        {canModeratePost(study) && (
          <label className="row">
            <input type="checkbox" checked={hiddenOnly} onChange={(event) => toggleHidden(event.target.checked)} />
            숨긴 글 보기
          </label>
        )}
        {canWritePost(study) && <Go href={`/studies/${study.studyId}/posts/new`}>글쓰기</Go>}
      </div>
      {posts.loading && <Loading />}
      {posts.error && <ErrorState message={posts.error.message} retry={posts.retry} />}
      {!posts.loading && !posts.error && posts.data && (
        <>
          {posts.data.totalElements === 0 ? (
            <Empty
              title={hiddenOnly ? "숨긴 글이 없어요" : noticeOnly ? "공지가 없어요" : "아직 게시글이 없어요"}
              description={
                hiddenOnly ? "운영진이 숨긴 글이 여기에 표시됩니다."
                  : canWritePost(study) ? "글쓰기에서 첫 글을 남겨 보세요." : "작성된 글이 여기에 표시됩니다."
              }
            />
          ) : (
            <ul className="stack">
              {posts.data.content.map((post) => (
                <PostRow
                  key={post.postId}
                  studyId={study.studyId}
                  post={post}
                  onRestore={hiddenOnly ? () => restore(post) : undefined}
                />
              ))}
            </ul>
          )}
          <Pager page={posts.data.page} totalPages={posts.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}
