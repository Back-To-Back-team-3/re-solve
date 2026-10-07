import type { Metadata } from "next";
import { Suspense } from "react";
import { Shell } from "@/components/shell";
import "@/styles/globals.css";
export const metadata: Metadata = {
  title: "Re:Solve — 함께 자라는 코딩 실력",
  description: "알고리즘 펫과 함께하는 코딩테스트 연습 목업",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html:
              "try{document.documentElement.dataset.theme=localStorage.getItem('resolve:theme')||'light'}catch{}",
          }}
        />
      </head>
      <body>
        <Suspense fallback={<div className="container">불러오는 중…</div>}>
          <Shell>{children}</Shell>
        </Suspense>
      </body>
    </html>
  );
}
