/** 공개 예제 또는 최대 다섯 직접 입력을 선택하고 JSON 원문을 보존한다. */
export function runInputs(names: string[], rows: Record<string, string>[], useSamples: boolean) {
  if (rows.length > 5 || (!useSamples && !rows.length)) {
    throw new Error("공개 예제 또는 직접 입력을 선택해 주세요. 직접 입력은 최대 5개입니다.");
  }
  return rows.map((row, index) => ({
    argumentTexts: Object.fromEntries(names.map((name) => {
      const text = row[name] ?? "";
      try { JSON.parse(text); }
      catch { throw new Error(`직접 입력 ${index + 1}의 ${name}을 JSON 형식으로 입력해 주세요.`); }
      return [name, text];
    })),
  }));
}
