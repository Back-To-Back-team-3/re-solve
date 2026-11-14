import { Suspense } from "react";
import { Loading } from "@/components/ui";
import { CallbackPage } from "@/features/account";

export default function Page() {
  return (
    <Suspense fallback={<Loading />}>
      <CallbackPage />
    </Suspense>
  );
}
