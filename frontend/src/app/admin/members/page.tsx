import { Suspense } from "react";
import { Loading } from "@/components/ui";
import { AdminMembersPage } from "@/features/account-admin";

export default function Page() {
  return (
    <Suspense fallback={<Loading />}>
      <AdminMembersPage />
    </Suspense>
  );
}
