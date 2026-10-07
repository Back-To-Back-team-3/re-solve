import { ExamResult } from "@/features/exams";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <ExamResult id={id} />;
}
