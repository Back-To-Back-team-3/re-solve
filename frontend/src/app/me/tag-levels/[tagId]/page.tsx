import { redirect } from "next/navigation";

export default function Page() {
  redirect("/mypage?tab=skill");
}
