import { ExamRoom } from "@/features/exams-room";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <ExamRoom id={id} />;
}
