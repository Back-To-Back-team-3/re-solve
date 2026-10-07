import { ProblemPage } from "@/features/problem";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <ProblemPage id={id} />;
}
