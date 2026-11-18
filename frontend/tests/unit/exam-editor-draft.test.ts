import { afterEach, expect, it, vi } from "vitest";
import { readExamDraft, writeExamDraft } from "@/services/exam-editor-draft";

afterEach(() => vi.unstubAllGlobals());
it("시험 Draft의 언어·코드·순번을 함께 보존한다", () => {
  const values = new Map<string, string>();
  vi.stubGlobal("localStorage", { getItem: (key: string) => values.get(key), setItem: (key: string, value: string) => values.set(key, value) });
  writeExamDraft("42", "1", "python3", "print(1)", "7");
  expect(readExamDraft("42", "1")).toEqual({ language: "python3", code: "print(1)", seq: "7" });
});
it("언어 필드가 없던 기존 Draft도 복원한다", () => {
  vi.stubGlobal("localStorage", { getItem: () => JSON.stringify({ code: "legacy", seq: "2" }) });
  expect(readExamDraft("42", "1")).toEqual({ code: "legacy", seq: "2" });
});
it("지원하지 않는 언어가 기록된 Draft를 다른 언어 코드로 복원하지 않는다", () => {
  vi.stubGlobal("localStorage", { getItem: () => JSON.stringify({ language: "unknown", code: "invalid", seq: "2" }) });
  expect(readExamDraft("42", "1")).toBeNull();
});
