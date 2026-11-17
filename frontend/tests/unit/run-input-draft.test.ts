import { afterEach, describe, expect, it, vi } from "vitest";
import { readRunInputDraft, writeRunInputDraft } from "@/services/run-input-draft";

afterEach(() => vi.unstubAllGlobals());

describe("직접 입력 자동 저장", () => {
  it("작성 중인 JSON과 큰 정수 원문을 검증·변환 없이 저장하고 복구한다", () => {
    const values = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    });
    const draft = { useSamples: false, customInputs: [{
      arguments: { a: { kind: "TEXT" as const, text: "[9223372036854775807," } },
      expectedOutput: { kind: "TEXT" as const, text: "9007199254740993" },
    }] };
    expect(writeRunInputDraft("member:practice:revision", draft)).toBe(true);
    expect(readRunInputDraft("member:practice:revision")).toEqual(draft);
    expect(readRunInputDraft("other-member:practice:revision")).toBeNull();
  });

  it.each([
    "{broken", '{"useSamples":true,"customInputs":[{}]}',
    '{"useSamples":true,"customInputs":[{"arguments":{"a":{"kind":"TEXT","text":32}}}]}',
  ])("손상된 저장 자료를 복구하지 않는다: %s", (raw) => {
    vi.stubGlobal("localStorage", { getItem: () => raw });
    expect(readRunInputDraft("key")).toBeNull();
  });

  it("저장소 접근 실패를 호출자에게 반환한다", () => {
    vi.stubGlobal("localStorage", {
      getItem: () => { throw new Error("차단"); },
      setItem: () => { throw new Error("차단"); },
    });
    expect(readRunInputDraft("key")).toBeNull();
    expect(writeRunInputDraft("key", { useSamples: true, customInputs: [] })).toBe(false);
  });
});
