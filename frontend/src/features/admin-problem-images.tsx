"use client";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import Markdown, { defaultUrlTransform } from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { actionMessage } from "@/services/problem-authoring";

function ProtectedImage({ id, alt }: { id: string; alt: string }) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let alive = true;
    let objectUrl = "";
    adminProblemApi.getImage(id).then((blob) => {
      if (!alive) return;
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl);
    }).catch((failure: unknown) => { if (alive) setError(actionMessage(failure)); });
    return () => { alive = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [id]);
  if (error) return <span className="error-text">이미지: {error}</span>;
  return url ? <Image unoptimized src={url} alt={alt} width={800} height={400} className="h-auto w-auto max-w-full" />
    : <span>이미지 조회 중…</span>;
}

const markdownComponents: Components = { img: ({ src, alt }) => {
  const match = typeof src === "string" ? /^image:([1-9][0-9]*)$/.exec(src) : null;
  return match ? <ProtectedImage key={match[1]} id={match[1]} alt={alt ?? "문제 이미지"} />
    : <span>관리자 업로드 이미지 참조를 사용해 주세요.</span>;
} };
export function AdminMarkdown({ text }: { text: string }) {
  return <div className="markdown"><Markdown remarkPlugins={[remarkGfm]} skipHtml
    urlTransform={(url) => /^image:[1-9][0-9]*$/.test(url) ? url : defaultUrlTransform(url)}
    components={markdownComponents}>{text}</Markdown></div>;
}

export function AdminProblemImages({ insert, onBusyChange }: {
  insert: (markdown: string) => void; onBusyChange: (busy: boolean) => void;
}) {
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [imageId, setImageId] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const request = useRef<{ file: File; key: string } | null>(null);
  async function upload() {
    if (!file || busy || imageId) return;
    setError("");
    if (file.size > 5 * 1024 * 1024 || !["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
      setError("PNG·JPEG·WebP, 5MiB 이하 이미지를 선택해 주세요."); return;
    }
    const original = request.current?.file === file ? request.current : { file, key: crypto.randomUUID() };
    request.current = original; setBusy(true); onBusyChange(true);
    try {
      const result = await adminProblemApi.uploadImage(original.file, original.key);
      setImageId(result.imageId);
      insert(`\n![이미지](image:${result.imageId})\n`);
      router.refresh();
    }
    catch (failure) { setError(actionMessage(failure)); }
    finally { setBusy(false); onBusyChange(false); }
  }
  return <div className="space-y-2 border-t-2 pt-3" aria-label="본문 이미지 첨부">
    <div className="flex flex-wrap items-center gap-3">
    <label className="field">이미지 첨부<input aria-label="업로드 이미지" type="file" accept="image/png,image/jpeg,image/webp" disabled={busy}
      onChange={(event) => { setFile(event.target.files?.[0] ?? null); setImageId(""); setError(""); }} /></label>
    <Button type="button" className="small" disabled={!file || busy || Boolean(imageId)} onClick={upload}>{busy ? "첨부 중…" : "이미지 첨부·재요청"}</Button>
    </div>
    <small className="muted">PNG·JPEG·WebP, 최대 5MiB. 첨부하면 본문의 커서 위치에 이미지가 삽입됩니다.</small>
    {error && <p className="error-text">{error}</p>}
    {imageId && <>
      <p role="status">본문에 이미지를 첨부했습니다.</p>
      <details><summary>첨부 이미지 미리보기</summary><div className="max-w-xs"><AdminMarkdown text={`![업로드 미리보기](image:${imageId})`} /></div></details>
    </>}
  </div>;
}
