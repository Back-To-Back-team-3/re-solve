"use client";
import { useRef, useState, type FormEvent } from "react";
import { useParams, useRouter } from "next/navigation";
import { actionErrorMessage, studyApi, studyPostApi } from "@/services/api";
import { useResource } from "@/services/hooks";
import type { StudyDetail } from "@/services/types";
import { Button, ErrorState, Go, Heading, Loading } from "@/components/ui";
import { POST_CONTENT_MAX, POST_TITLE_MAX, canModeratePost, canWritePost, postInputError } from "@/features/study-post-policy";

export function StudyPostWrite() {
  const { id } = useParams<{ id: string }>();
  const study = useResource(() => studyApi.getDetail(id), `${id}:post-write`);
  return (
    <div className="container stack">
      <Go className="small" href={`/studies/${id}?tab=board`}>← 게시판으로 돌아가기</Go>
      <Heading title="글쓰기" eyebrow="STUDY BOARD" />
      {study.loading ? <Loading /> : study.error ? (
        <ErrorState message={study.error.message} retry={study.retry} />
      ) : study.data && <PostWriteForm study={study.data} />}
    </div>
  );
}

function PostWriteForm({ study }: { study: StudyDetail }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const inFlight = useRef(false);
  const allowed = canWritePost(study);
  const operator = canModeratePost(study);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || !allowed) return;
    const values = new FormData(event.currentTarget);
    const title = String(values.get("title") ?? "");
    const content = String(values.get("content") ?? "");
    const invalid = postInputError(title, content);
    if (invalid) { setError(invalid); return; }
    inFlight.current = true;
    setBusy(true); setError("");
    let created = false;
    try {
      const result = await studyPostApi.create(study.studyId, {
        title: title.trim(),
        content,
        isNotice: operator && values.get("isNotice") === "on",
      });
      created = true;
      router.push(`/studies/${study.studyId}/posts/${result.postId}`);
      router.refresh();
    } catch (failure) {
      setError(actionErrorMessage(failure));
    } finally {
      // 성공하면 화면을 떠나므로 버튼을 다시 열지 않아 중복 작성을 막는다.
      if (!created) { inFlight.current = false; setBusy(false); }
    }
  }

  return (
    <form className="card stack" onSubmit={submit}>
      <p>{study.name}</p>
      {!allowed && <p role="alert">종료되지 않은 스터디의 승인된 구성원만 글을 쓸 수 있습니다.</p>}
      {error && <p role="alert" className="error-text">{error}</p>}
      <fieldset className="stack" disabled={busy || !allowed}>
        <label className="field">제목<input className="input" name="title" required maxLength={POST_TITLE_MAX} /></label>
        <label className="field">본문<textarea className="input" name="content" required maxLength={POST_CONTENT_MAX} rows={10} /></label>
        {operator && <label className="row"><input type="checkbox" name="isNotice" />공지로 등록</label>}
        <Button type="submit" className="primary">{busy ? "등록 중…" : "등록"}</Button>
      </fieldset>
    </form>
  );
}
