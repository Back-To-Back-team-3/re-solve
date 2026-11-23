"use client";
import { useState } from "react";
import { MessageSquare } from "lucide-react";
import { studyPostApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { PostSummary, StudyDetail } from "@/services/types";
import { Badge, Empty, ErrorState, Go, Loading } from "@/components/ui";
import { canWritePost } from "@/features/study-post-policy";
import { Pager } from "./problem-list";

/** 목록·상세에 쓰는 작성 시각 표기. 서버 값은 +09:00 ISO 문자열이라 날짜·분까지만 자른다. */
export function postTime(value: string): string {
  return value.slice(0, 16).replace("T", " ");
}

function PostRow({ studyId, post }: { studyId: string; post: PostSummary }) {
  return (
    <li className="card stack-sm">
      <div className="row">
        {post.isPinned && <Badge tone="accent">고정</Badge>}
        {post.isNotice && <Badge tone="success">공지</Badge>}
        <Go href={`/studies/${studyId}/posts/${post.postId}`}>
          <b>{post.title}</b>
        </Go>
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
  const [noticeOnly, setNoticeOnly] = useState(false);
  const [page, setPage] = useState(0);
  const posts = useResource(
    () => studyPostApi.list(study.studyId, { isNotice: noticeOnly, page, size: 20 }),
    `${study.studyId}:posts:${noticeOnly}:${page}:${version}`,
  );

  function toggleNotice(next: boolean) {
    setNoticeOnly(next);
    setPage(0);
  }

  return (
    <div className="stack">
      <div className="row between">
        <label className="row">
          <input type="checkbox" checked={noticeOnly} onChange={(event) => toggleNotice(event.target.checked)} />
          공지만 보기
        </label>
        {canWritePost(study) && <Go href={`/studies/${study.studyId}/posts/new`}>글쓰기</Go>}
      </div>
      {posts.loading && <Loading />}
      {posts.error && <ErrorState message={posts.error.message} retry={posts.retry} />}
      {!posts.loading && !posts.error && posts.data && (
        <>
          {posts.data.totalElements === 0 ? (
            <Empty
              title={noticeOnly ? "공지가 없어요" : "아직 게시글이 없어요"}
              description={canWritePost(study) ? "글쓰기에서 첫 글을 남겨 보세요." : "작성된 글이 여기에 표시됩니다."}
            />
          ) : (
            <ul className="stack">
              {posts.data.content.map((post) => (
                <PostRow key={post.postId} studyId={study.studyId} post={post} />
              ))}
            </ul>
          )}
          <Pager page={posts.data.page} totalPages={posts.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}
