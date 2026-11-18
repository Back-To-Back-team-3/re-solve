"use client";
import { useId, useState, type ChangeEvent } from "react";
import { runEditText, runInputError, runInputExample, type RunInputValue } from "@/services/run-inputs";
import type { ProblemFunctionParamType } from "@/services/types";

const typeLabel: Record<string, string> = {
  INT32: "정수 (32비트)", INT64: "정수 (64비트)", FLOAT32: "실수 (32비트)", FLOAT64: "실수 (64비트)",
  STRING: "문자열", BOOLEAN: "참·거짓", ARRAY: "배열 · JSON", OBJECT: "객체 · JSON",
};

export function runValueTypeLabel(type: ProblemFunctionParamType): string {
  return typeLabel[type.kind] ?? type.kind;
}

/** 모든 타입을 원문으로 편집하고 선언 타입의 오류를 필드 옆에 표시한다. */
export function EditorRunValue({ name, type, value, onChange, hintId, optional = false }: {
  name: string; type: ProblemFunctionParamType; value: RunInputValue; hintId?: string; optional?: boolean;
  onChange: (value: RunInputValue) => void;
}) {
  const id = useId();
  const [touched, setTouched] = useState(false);
  const text = runEditText(type, value);
  const error = touched && !(optional && text === "") ? runInputError(type, value, name) : null;
  const multiline = ["ARRAY", "OBJECT", "STRING"].includes(type.kind);
  const control = {
    id, "aria-label": name, "aria-invalid": !!error,
    "aria-describedby": [hintId, error ? `${id}-error` : undefined].filter(Boolean).join(" ") || undefined,
    className: "input mono min-w-0 w-full", value: text, placeholder: runInputExample(type),
    onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      setTouched(true);
      onChange({ kind: "TEXT", text: event.target.value });
    },
  };
  return <div className="min-w-0 space-y-2">
    <div className="min-w-0 space-y-1">
      {multiline ? <textarea {...control} className={`${control.className} min-h-0! resize-y`} rows={1} />
        : <input {...control} type="text" inputMode={type.kind.startsWith("INT") ? "numeric" : type.kind.startsWith("FLOAT") ? "decimal" : "text"} />}
      {error && <p id={`${id}-error`} role="alert" className="error-text text-sm [overflow-wrap:anywhere]">{error}</p>}
    </div>
  </div>;
}
