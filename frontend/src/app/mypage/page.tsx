import { Suspense } from "react";
import { MyPage } from "@/features/account";
export default function Page() {
  return <Suspense><MyPage /></Suspense>;
}
