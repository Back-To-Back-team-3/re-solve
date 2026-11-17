import type { RunInputValue, RunTestCase } from "@/services/run-inputs";

export type RunInputDraft = { useSamples: boolean; customInputs: RunTestCase[] };

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function inputValue(value: unknown): value is RunInputValue {
  if (!record(value)) return false;
  if (value.kind === "TEXT" || value.kind === "SCALAR") return typeof value.text === "string";
  if (value.kind === "ARRAY") return Array.isArray(value.items) && value.items.every(inputValue);
  return value.kind === "OBJECT" && record(value.fields) && Object.values(value.fields).every(inputValue);
}

/** 편집 중 잘못된 JSON도 파싱하지 않고 원문 상태 그대로 복구한다. */
export function readRunInputDraft(key: string): RunInputDraft | null {
  try {
    const data: unknown = JSON.parse(localStorage.getItem(`re-solve:run-input:${key}`) ?? "null");
    if (!record(data) || typeof data.useSamples !== "boolean" || !Array.isArray(data.customInputs)
      || data.customInputs.length > 10 || !data.customInputs.every((row: unknown) => record(row)
        && record(row.arguments) && Object.values(row.arguments).every(inputValue)
        && (row.expectedOutput === undefined || inputValue(row.expectedOutput)))) return null;
    return { useSamples: data.useSamples, customInputs: data.customInputs };
  } catch { return null; }
}

export function writeRunInputDraft(key: string, draft: RunInputDraft): boolean {
  try {
    localStorage.setItem(`re-solve:run-input:${key}`, JSON.stringify(draft));
    return true;
  } catch { return false; }
}
