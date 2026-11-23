import { StudyPostDetail } from "@/features/study-post-detail";
export default async function Page({ params }: { params: Promise<{ id: string; postId: string }> }) {
  const { id, postId } = await params;
  return <StudyPostDetail studyId={id} postId={postId} />;
}
