"use client";
import Link from "next/link";
import { Dialog } from "radix-ui";
import { X, ArrowRight, TriangleAlert } from "lucide-react";
import {
  createContext,
  useContext,
  useState,
  useRef,
  type ReactNode,
} from "react";
import { useRouter, usePathname } from "next/navigation";
import { api } from "@/services/mock-api";
export function Button({
  children,
  className = "",
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={`btn ${className}`} {...props}>
      {children}
    </button>
  );
}
export function Go({
  href,
  children,
  className = "",
}: {
  href: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Link className={`btn ${className}`} href={href}>
      {children}
    </Link>
  );
}
export function Badge({
  children,
  tone = "",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function Heading({
  title,
  description,
  eyebrow,
  action,
}: {
  title: string;
  description?: string;
  eyebrow?: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
export function Section({
  title,
  href,
  children,
}: {
  title: string;
  href?: string;
  children: ReactNode;
}) {
  return (
    <section className="card">
      <div className="section-heading">
        <h2>{title}</h2>
        {href && (
          <Link className="row" href={href}>
            전체 보기 <ArrowRight size={16} />
          </Link>
        )}
      </div>
      {children}
    </section>
  );
}
export function Tabs({
  items,
  value,
  onChange,
}: {
  items: { id: string; label: string }[];
  value: string;
  onChange: (s: string) => void;
}) {
  return (
    <div className="tabs" aria-label="화면 선택">
      {items.map((t) => (
        <button
          key={t.id}
          type="button"
          className="tab"
          aria-pressed={t.id === value}
          onClick={() => onChange(t.id)}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
export function Modal({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean;
  onOpenChange: (s: boolean) => void;
  title: string;
  description: string;
  children: ReactNode;
}) {
  const returnFocus = useRef<HTMLElement | null>(null);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="modal-overlay" />
        <Dialog.Content
          className="modal"
          onOpenAutoFocus={() => {
            returnFocus.current = document.activeElement as HTMLElement;
          }}
          onCloseAutoFocus={(e) => {
            e.preventDefault();
            returnFocus.current?.focus();
          }}
        >
          <div className="row between">
            <Dialog.Title className="modal-title">{title}</Dialog.Title>
            <Dialog.Close className="btn icon-btn" aria-label="닫기">
              <X size={18} />
            </Dialog.Close>
          </div>
          <Dialog.Description className="modal-description">
            {description}
          </Dialog.Description>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
export function Empty({
  title = "아직 기록이 없어요",
  description = "첫 번째 기록을 만들어 보세요.",
  action,
}: {
  title?: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="pixel">NO DATA YET</span>
      <h3>{title}</h3>
      <p className="muted">{description}</p>
      {action}
    </div>
  );
}
export function Loading() {
  return (
    <div className="card skeleton" role="status">
      <span className="pixel loading-pet">LOADING...</span>
    </div>
  );
}
export function ErrorState({
  message,
  retry,
}: {
  message: string;
  retry?: () => void;
}) {
  return (
    <div className="card empty" role="alert">
      <TriangleAlert />
      <h2>불러오지 못했어요</h2>
      <p>{message}</p>
      {retry && <Button onClick={retry}>다시 시도</Button>}
      <Go href="/">홈으로</Go>
    </div>
  );
}
export function NotFound({ what = "페이지" }: { what?: string }) {
  return (
    <div className="container">
      <Empty
        title={`${what}를 찾을 수 없어요`}
        description="주소를 확인하거나 홈에서 다시 시작해 주세요."
        action={<Go href="/">홈으로</Go>}
      />
    </div>
  );
}
export function Progress({ value }: { value: number }) {
  return (
    <div
      className="progress"
      role="progressbar"
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      {Array.from({ length: 12 }, (_, i) => (
        <span key={i} className={i < (value / 100) * 12 ? "filled" : ""} />
      ))}
    </div>
  );
}
const ToastContext = createContext<(s: string) => void>(() => {});
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState("");
  return (
    <ToastContext.Provider
      value={(s) => {
        setMessage(s);
        setTimeout(
          () => setMessage((current) => (current === s ? "" : current)),
          4000,
        );
      }}
    >
      {children}
      {message && (
        <div className="toast" role="status">
          {message}
        </div>
      )}
    </ToastContext.Provider>
  );
}
export const useToast = () => useContext(ToastContext);
export function useAction() {
  const router = useRouter(),
    path = usePathname(),
    toast = useToast();
  return async (fn: () => Promise<unknown>, success?: string, auth = true) => {
    try {
      if (auth && !(await api.currentUser())) {
        router.push(`/login?returnTo=${encodeURIComponent(path)}`);
        return false;
      }
      await fn();
      if (success) toast(success);
      return true;
    } catch (e) {
      toast(
        e instanceof Error
          ? e.message
          : "저장하지 못했습니다. 다시 시도해 주세요.",
      );
      return false;
    }
  };
}
export function date(value: number) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(value);
}
