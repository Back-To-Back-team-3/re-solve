"use client";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import Markdown, { defaultUrlTransform } from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { ImagePlus } from "lucide-react";
import { HoverCard } from "radix-ui";
import { Button } from "@/components/ui";
import { adminProblemApi } from "@/services/api";
import { actionMessage } from "@/services/problem-authoring";

function ProtectedImage({ id, alt }: { id: string; alt: string }) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let alive = true;
    let objectUrl = "";
    adminProblemApi.getImage(id).then((blob) => {
      if (!alive) return;
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl);
    }).catch((failure: unknown) => { if (alive) setError(actionMessage(failure)); });
    return () => { alive = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [id, retry]);
  if (error) return <span className="error-text" role="alert">이미지: {error} <button type="button" className="btn small"
    onClick={() => { setError(""); setRetry((value) => value + 1); }}>이미지 다시 불러오기</button></span>;
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

function ImagePreview({ id }: { id: string }) {
  const [open, setOpen] = useState(false);
  const content = useRef<HTMLDivElement>(null);
  return <HoverCard.Root open={open} onOpenChange={setOpen} openDelay={200}>
    <HoverCard.Trigger asChild><button type="button" className="btn small mono" aria-label={`image:${id} 미리보기`}
      onFocus={() => setOpen(true)} onBlur={(event) => {
        if (!(event.relatedTarget instanceof Node && content.current?.contains(event.relatedTarget))) setOpen(false);
      }}>image:{id}</button></HoverCard.Trigger>
    <HoverCard.Portal><HoverCard.Content ref={content} onBlur={(event) => {
      if (!(event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget))) setOpen(false);
    }} role="tooltip" side="bottom" align="start" sideOffset={8} collisionPadding={12}
      className="z-50 w-64 max-w-[calc(100vw-24px)] border-2 bg-surface p-3">
      <p className="mono mb-2">image:{id}</p>
      <div className="max-h-64 overflow-auto"><ProtectedImage id={id} alt={`image:${id} 첨부 이미지`} /></div>
      <HoverCard.Arrow className="fill-current" />
    </HoverCard.Content></HoverCard.Portal>
  </HoverCard.Root>;
}

export function AdminProblemImages({ text, insert, onBusyChange }: {
  text: string; insert: (markdown: string) => void; onBusyChange: (busy: boolean) => void;
}) {
  const router = useRouter();
  const imageIds = [...new Set([...text.matchAll(/!\[[^\]]*\]\(image:([1-9][0-9]*)\)/g)].map((match) => match[1]))];
  const [imageId, setImageId] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const picker = useRef<HTMLInputElement>(null);
  const lock = useRef(false);
  const request = useRef<{ file: File; key: string } | null>(null);
  async function upload(original: { file: File; key: string }) {
    if (lock.current) return;
    lock.current = true; setError(""); setFailed(false); setBusy(true); onBusyChange(true);
    try {
      const result = await adminProblemApi.uploadImage(original.file, original.key);
      setImageId(result.imageId);
      insert(`\n![이미지](image:${result.imageId})\n`);
      router.refresh();
    }
    catch (failure) { setError(actionMessage(failure)); setFailed(true); }
    finally { lock.current = false; setBusy(false); onBusyChange(false); }
  }
  return <div className="space-y-2 border-2 p-3" aria-label="본문 이미지 첨부">
    <div role="toolbar" aria-label="본문 도구 모음" className="flex flex-wrap items-center gap-3">
      <Button type="button" className="small" aria-label="이미지 첨부" disabled={busy} onClick={() => picker.current?.click()}>
        <ImagePlus size={16} aria-hidden="true" /> 이미지
      </Button>
      <input ref={picker} hidden aria-label="업로드 이미지" type="file" accept="image/png,image/jpeg,image/webp" disabled={busy}
        onChange={(event) => {
          const file = event.target.files?.[0]; event.target.value = "";
          if (!file || lock.current) return;
          setImageId(""); setError(""); setFailed(false); request.current = null;
          if (file.size > 5 * 1024 * 1024 || !["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
            setError("PNG·JPEG·WebP, 5MiB 이하 이미지를 선택해 주세요."); return;
          }
          const original = { file, key: crypto.randomUUID() };
          request.current = original; void upload(original);
        }} />
      {failed && <Button type="button" className="small" onClick={() => { if (request.current) void upload(request.current); }}>이미지 다시 시도</Button>}
      {busy && <span role="status">이미지 첨부 중…</span>}
    </div>
    <small className="muted">PNG·JPEG·WebP, 최대 5MiB. 첨부하면 본문의 커서 위치에 이미지가 삽입됩니다.</small>
    {error && <p className="error-text" role="alert">{error}</p>}
    {imageId && <>
      <p role="status">본문에 이미지를 첨부했습니다.</p>
    </>}
    {imageIds.length > 0 && <ul aria-label="첨부 이미지 목록" className="flex flex-wrap gap-2">
      {imageIds.map((id) => <li key={id}><ImagePreview id={id} /></li>)}
    </ul>}
  </div>;
}
