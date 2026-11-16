import { redirect } from "next/navigation";

export function TagLevelRedirectPage() {
  return redirect("/mypage?tab=skill");
}
