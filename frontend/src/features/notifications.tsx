"use client";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell, BellOff } from "lucide-react";
import * as Popover from "@radix-ui/react-popover";
import { notificationApi } from "@/services/api";
import { useNotificationSSE } from "@/services/hooks";
import type { NotificationItem } from "@/services/types";

export function formatRelativeTime(isoString: string): string {
  const diff = Date.now() - new Date(isoString).getTime();
  const rtf = new Intl.RelativeTimeFormat("ko", { numeric: "auto" });
  if (diff < 60_000) return rtf.format(-Math.floor(diff / 1000), "second");
  if (diff < 3_600_000) return rtf.format(-Math.floor(diff / 60_000), "minute");
  if (diff < 86_400_000) return rtf.format(-Math.floor(diff / 3_600_000), "hour");
  return rtf.format(-Math.floor(diff / 86_400_000), "day");
}

function NotificationDropdown({
  open,
  onOpenChange,
  unreadCount,
  onUnreadCountChange,
  reloadKey,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unreadCount: number;
  onUnreadCountChange: (count: number) => void;
  reloadKey: number;
}) {
  const router = useRouter();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoaded(false);
    setFailed(false);
    notificationApi
      .getList({ page: 0, size: 20 })
      .then((res) => setItems(res.content))
      .catch(() => setFailed(true))
      .finally(() => setLoaded(true));
  }, [open, reloadKey]);

  const handleMarkRead = useCallback(
    (item: NotificationItem) => {
      if (!item.isRead) {
        setItems((prev) =>
          prev.map((n) =>
            n.notificationId === item.notificationId ? { ...n, isRead: true } : n,
          ),
        );
        onUnreadCountChange(Math.max(0, unreadCount - 1));
        notificationApi.markRead(item.notificationId).catch(() => {});
      }
      onOpenChange(false);
      router.push(item.linkUrl);
    },
    [router, onOpenChange, onUnreadCountChange, unreadCount],
  );

  const handleMarkAllRead = useCallback(() => {
    setItems((prev) => prev.map((n) => ({ ...n, isRead: true })));
    onUnreadCountChange(0);
    notificationApi.markAllRead().catch(() => {});
  }, [onUnreadCountChange]);

  return (
    <Popover.Portal>
      <Popover.Content
        className="w-80 rounded bg-surface border-2 border-foreground shadow-lg"
        align="end"
        sideOffset={8}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b-2 border-foreground">
          <span className="text-sm font-semibold">알림</span>
          {unreadCount > 0 && (
            <button
              className="text-xs text-muted hover:text-foreground transition-colors"
              onClick={handleMarkAllRead}
            >
              모두 읽음
            </button>
          )}
        </div>
        {!loaded ? (
          <div className="flex justify-center py-8 text-sm text-muted">불러오는 중...</div>
        ) : failed ? (
          <div className="flex justify-center py-8 text-sm text-muted" role="alert">
            알림을 불러오지 못했습니다
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-8 text-sm text-muted">
            <BellOff size={20} />
            <span>새 알림이 없습니다</span>
          </div>
        ) : (
          <ul className="max-h-80 overflow-y-auto">
            {items.map((item) => (
              <li key={item.notificationId} className="border-b-2 border-foreground last:border-b-0">
                <button
                  className={`w-full text-left px-4 py-3 flex flex-col gap-0.5 hover:bg-primary/10 transition-colors${item.isRead ? "" : " border-l-2 border-primary bg-primary/5"}`}
                  onClick={() => handleMarkRead(item)}
                >
                  <span className="text-sm font-medium line-clamp-1">{item.title}</span>
                  <span className="text-xs text-muted line-clamp-1">{item.content}</span>
                  <span className="text-xs text-muted">{formatRelativeTime(item.createdAt)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Popover.Content>
    </Popover.Portal>
  );
}

export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // 배지는 보조 정보라 조회 실패 시 0으로 두고 화면을 막지 않는다.
    notificationApi
      .getUnreadCount()
      .then((res) => setUnreadCount(res.unreadCount))
      .catch(() => {});
  }, []);

  const handleSSEEvent = useCallback(() => {
    setUnreadCount((prev) => prev + 1);
    if (open) {
      setReloadKey((k) => k + 1);
    }
  }, [open]);

  useNotificationSSE(handleSSEEvent);

  const badge = unreadCount > 99 ? "99+" : unreadCount > 0 ? String(unreadCount) : null;

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button className="icon-btn relative" aria-label="알림">
          <Bell size={18} />
          {badge && (
            <span className="absolute -top-1 -right-1 min-w-4 h-4 rounded-full bg-primary text-background text-[10px] font-bold leading-4 text-center px-0.5">
              {badge}
            </span>
          )}
        </button>
      </Popover.Trigger>
      <NotificationDropdown
        open={open}
        onOpenChange={setOpen}
        unreadCount={unreadCount}
        onUnreadCountChange={setUnreadCount}
        reloadKey={reloadKey}
      />
    </Popover.Root>
  );
}
