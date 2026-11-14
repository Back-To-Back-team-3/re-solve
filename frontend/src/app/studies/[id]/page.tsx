import { StudyPage } from "@/features/studies";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <StudyPage id={id} />;
}
