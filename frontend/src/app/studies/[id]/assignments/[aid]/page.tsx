import { AssignmentDetail } from "@/features/study-assignments";

export default async function Page({
  params,
}: {
  params: Promise<{ id: string; aid: string }>;
}) {
  const { id, aid } = await params;
  return <AssignmentDetail studyId={id} assignmentId={aid} />;
}
