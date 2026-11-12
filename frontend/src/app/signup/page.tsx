import { Suspense } from "react";
import { Loading } from "@/components/ui";
import { AuthPage } from "@/features/account";

export default function Page() {
  return (
    <Suspense fallback={<Loading />}>
      <AuthPage />
    </Suspense>
  );
}
