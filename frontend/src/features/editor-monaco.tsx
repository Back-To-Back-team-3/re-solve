"use client";
import dynamic from "next/dynamic";
import type { Language } from "@/services/types";
import { loader } from "@monaco-editor/react";
const Monaco = dynamic(() => import("@monaco-editor/react"), {
  ssr: false,
  loading: () => <div className="skeleton pixel">LOADING EDITOR...</div>,
});
loader.config({ paths: { vs: "/monaco/vs" } });

export function EditorMonaco({ code, fontSize, language, onChange }: {
  code: string; fontSize: number; language: Language; onChange: (value: string) => void;
}) {
  function theme(monaco: typeof import("monaco-editor")) {
    const styles = getComputedStyle(document.documentElement);
    const color = (name: string) => styles.getPropertyValue(name).trim();
    monaco.editor.defineTheme("resolve", {
      base: "vs-dark", inherit: true, rules: [],
      colors: {
        "editor.background": color("--editor-bg"), "editor.foreground": color("--editor-text"),
        "editorLineNumber.foreground": color("--editor-muted"), "editorCursor.foreground": color("--primary"),
      },
    });
    monaco.editor.setTheme("resolve");
  }
  // 언어는 부모 Buffer의 언어별 key와 함께 변경한다.
  return <Monaco height="100%" value={code} language={language === "python3" ? "python" : language}
    theme="resolve" beforeMount={theme} onChange={(value) => onChange(value ?? "")}
    onMount={(editor, monaco) => {
      const observer = new MutationObserver(() => theme(monaco));
      observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
      editor.onDidDispose(() => observer.disconnect());
    }}
    options={{ editContext: false, fontSize, minimap: { enabled: false }, scrollBeyondLastLine: false,
      automaticLayout: true, tabSize: 4, padding: { top: 20 }, ariaLabel: "코드 편집기", wordWrap: "on" }} />;
}
