import { Suspense } from "react";
import { DiagnosisPage } from "@/features/diagnosis";
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  return <Suspense><DiagnosisPage id={(await params).id} /></Suspense>;
}
