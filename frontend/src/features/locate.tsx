"use client";
import Link from "next/link";
import { api } from "@/services/mock-api";
import { useResource } from "@/services/hooks";
import { useCatalog } from "@/services/catalog";
import { Heading } from "@/components/ui";
export function LocatePage() {
  const { problems } = useCatalog();
  const { data } = useResource(api.snapshot);
  const groups: [string, [string, string][]][] = [
    [
      "기본 화면",
      [
        ["/", "펫 홈"],
        ["/dashboard", "학습 현황"],
        ["/problems", "문제 목록"],
        ["/studies", "스터디 목록"],
        ["/exams", "모의 코테 목록"],
        ["/contests", "공개대회 목록"],
      ],
    ],
    [
      "계정 · 생성",
      [
        ["/login", "로그인"],
        ["/signup", "회원가입"],
        ["/mypage", "마이페이지"],
        ["/studies/new", "스터디 만들기"],
        ["/exams/new", "시험 만들기"],
      ],
    ],
    [
      "필터 · 예외 상태",
      [
        ["/problems?keyword=없는문제", "검색 결과 없음"],
        ["/problems?tag=hash", "해시 문제"],
        ["/problems?difficulty=5", "난이도 5"],
        ["/problems?status=SOLVED", "해결한 문제"],
        ["/problems?page=1", "문제 2페이지"],
        ["/problems/missing", "잘못된 문제 ID"],
        ["/studies/missing", "잘못된 스터디 ID"],
        ["/exams/missing", "잘못된 시험 ID"],
        ["/missing", "404 페이지"],
      ],
    ],
    [
      "문제 상세",
      problems.map((p) => [`/problems/${p.id}`, `${p.id} ${p.title}`]),
    ],
    [
      "스터디 상세",
      (data?.studies || []).map((s) => [`/studies/${s.id}`, s.title]),
    ],
    [
      "시험 · 대회 상세",
      (data?.exams || []).map((e) => [
        `/${e.kind === "contest" ? "contests" : "exams"}/${e.id}`,
        e.title,
      ]),
    ],
    [
      "응시 · 결과",
      (data?.exams || []).flatMap((e) => [
        [`/exams/${e.id}/room`, `${e.title} · 응시`],
        [`/exams/${e.id}/result`, `${e.title} · 결과`],
      ]),
    ],
  ];
  return (
    <div className="container stack">
      <Heading
        title="모든 페이지 둘러보기"
        eyebrow="PAGE DIRECTORY"
        description="검토용 링크 모음입니다. 생성한 스터디와 시험도 목록에 반영됩니다. 응시 화면은 대기실에서 먼저 시작해 주세요."
      />
      <div className="review-grid">
        {groups.map(([title, links]) => (
          <section className="card" key={title}>
            <h2>{title}</h2>
            <div className="review-links">
              {links.map(([href, label]) => (
                <Link key={href} href={href}>
                  <span>
                    {label}
                    <small style={{ display: "block" }}>{href}</small>
                  </span>
                  <span>↗</span>
                </Link>
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
