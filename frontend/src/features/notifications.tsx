"use client";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell, BellOff } from "lucide-react";
import * as Popover from "@radix-ui/react-popover";
import { notificationApi } from "@/services/api";
import { useNotificationSSE } from "@/services/hooks";
import type { NotificationItem } from "@/services/types";

function formatRelativeTime(isoString: string): string {
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
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unreadCount: number;
  onUnreadCountChange: (count: number) => void;
}) {
  const router = useRouter();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!open) return;
    notificationApi.getList({ page: 0, size: 20 }).then((res) => {
      setItems(res.content);
      setLoaded(true);
    });
  }, [open]);

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
        className="notification-popover"
        align="end"
        sideOffset={8}
      >
        <div className="notification-header">
          <span className="notification-title">알림</span>
          {unreadCount > 0 && (
            <button
              className="notification-read-all"
              onClick={handleMarkAllRead}
            >
              모두 읽음
            </button>
          )}
        </div>
        {!loaded ? (
          <div className="notification-empty">불러오는 중...</div>
        ) : items.length === 0 ? (
          <div className="notification-empty">
            <BellOff size={20} />
            <span>새 알림이 없습니다</span>
          </div>
        ) : (
          <ul className="notification-list">
            {items.map((item) => (
              <li key={item.notificationId}>
                <button
                  className={`notification-item${item.isRead ? "" : " notification-item--unread"}`}
                  onClick={() => handleMarkRead(item)}
                >
                  <span className="notification-item-title">{item.title}</span>
                  <span className="notification-item-content">{item.content}</span>
                  <span className="notification-item-time">
                    {formatRelativeTime(item.createdAt)}
                  </span>
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

  useEffect(() => {
    notificationApi.getUnreadCount().then((res) => {
      setUnreadCount(res.unreadCount);
    });
  }, []);

  const handleSSEEvent = useCallback(() => {
    setUnreadCount((prev) => prev + 1);
    if (open) {
      // 드롭다운이 열려 있으면 목록 갱신을 위해 open을 토글한다.
      setOpen(false);
      setTimeout(() => setOpen(true), 0);
    }
  }, [open]);

  useNotificationSSE(handleSSEEvent);

  const badge = unreadCount > 99 ? "99+" : unreadCount > 0 ? String(unreadCount) : null;

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button className="icon-btn notification-bell" aria-label="알림">
          <Bell size={18} />
          {badge && <span className="notification-badge">{badge}</span>}
        </button>
      </Popover.Trigger>
      <NotificationDropdown
        open={open}
        onOpenChange={setOpen}
        unreadCount={unreadCount}
        onUnreadCountChange={setUnreadCount}
      />
    </Popover.Root>
  );
}
