"use client";
import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useWorkspace } from "@/stores/workspace";
import { Loading } from "@/components/ui";

export default function AdminLayout({ children }: { children: ReactNode }) {
  const member = useWorkspace((s) => s.member);
  const authReady = useWorkspace((s) => s.authReady);
  const router = useRouter();
  useEffect(() => {
    if (!authReady) return;
    if (!member || member.role !== "ADMIN") router.replace("/");
  }, [authReady, member, router]);
  if (!authReady) return <Loading />;
  if (!member || member.role !== "ADMIN") return null;
  return <>{children}</>;
}
