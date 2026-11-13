"use client";
import { useEffect } from "react";
import { Empty } from "@/components/ui";
import { labels } from "@/services/mock-api";
import { draftKey, useWorkspace } from "@/stores/workspace";
import type { Language, Problem } from "@/services/types";
import { Buffer } from "@/features/editor-buffer";

export function CodeEditor({
  problem,
  userId,
  examId,
  availableLanguages,
}: {
  problem: Problem;
  userId: string;
  examId?: string;
  availableLanguages?: Language[];
}) {
  const language = useWorkspace((s) => s.language),
    setLanguage = useWorkspace((s) => s.setLanguage),
    fontSize = useWorkspace((s) => s.fontSize),
    setFontSize = useWorkspace((s) => s.setFontSize);
  useEffect(() => {
    try {
      const stored = localStorage.getItem("resolve:editor");
      if (stored) {
        const data = JSON.parse(stored);
        if (["java", "python3", "cpp"].includes(data.language))
          setLanguage(data.language);
        if (data.fontSize >= 12 && data.fontSize <= 22)
          setFontSize(data.fontSize);
      }
    } catch {}
  }, [setLanguage, setFontSize]);
  const languages = availableLanguages ?? (["python3", "java", "cpp"] as const)
    .filter((value) => typeof problem.starterCodes[value] === "string");
  const selectedLanguage = languages.includes(language) ? language : languages[0];
  function settings(lang: Language, size: number) {
    window.dispatchEvent(new Event("resolve:flush"));
    setLanguage(lang);
    setFontSize(size);
    try {
      localStorage.setItem(
        "resolve:editor",
        JSON.stringify({ language: lang, fontSize: size }),
      );
    } catch {}
  }
  return (
    <div className="code-panel">
      <div className="editor-toolbar">
        <div className="row">
          <span className="pixel">CODE EDITOR</span>
          <select
            aria-label="코드 언어"
            className="input"
            value={selectedLanguage ?? ""}
            disabled={!selectedLanguage}
            onChange={(e) => settings(e.target.value as Language, fontSize)}
          >
            {languages.map((l) => (
              <option key={l} value={l}>
                {labels[l]}
              </option>
            ))}
          </select>
        </div>
        <select
          aria-label="글자 크기"
          className="input"
          value={fontSize}
          onChange={(e) => settings(selectedLanguage ?? language, Number(e.target.value))}
        >
          {[12, 14, 16, 18, 20, 22].map((n) => (
            <option key={n} value={n}>
              {n}px
            </option>
          ))}
        </select>
      </div>
      {selectedLanguage ? <Buffer
        key={JSON.stringify([draftKey(userId, problem.id, selectedLanguage, examId), problem.problemRevisionId])}
        problem={problem}
        userId={userId}
        examId={examId}
        language={selectedLanguage}
        fontSize={fontSize}
      /> : <Empty title="실행 가능한 언어가 없어요" description="지원 실행 환경을 확인한 뒤 다시 시도해 주세요." />}
    </div>
  );
}
