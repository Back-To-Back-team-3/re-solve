import type { ProblemFunctionParamType } from "@/services/types";

export type RunInputValue =
  | { kind: "SCALAR"; text: string }
  | { kind: "ARRAY"; items: RunInputValue[] }
  | { kind: "OBJECT"; fields: Record<string, RunInputValue> };
export type RunInputRow = Record<string, RunInputValue>;
export type RunTestCase = { arguments: RunInputRow; expectedOutput?: RunInputValue };
export type RunInputParameter = { name: string; inputType?: ProblemFunctionParamType };

/** 위치가 고정된 배열·객체의 필드는 미리 만들고 가변 배열은 빈 값으로 시작한다. */
export function createRunInput(type: ProblemFunctionParamType): RunInputValue {
  if (type.kind === "ARRAY") {
    return { kind: "ARRAY", items: (type.positionTypes ?? []).map(createRunInput) };
  }
  if (type.kind === "OBJECT") {
    return { kind: "OBJECT", fields: Object.fromEntries(
      Object.entries(type.properties ?? {}).map(([name, child]) => [name, createRunInput(child)]),
    ) };
  }
  return { kind: "SCALAR", text: type.kind === "BOOLEAN" ? "false" : "" };
}

/** 배열의 인덱스에 해당하는 선언 타입을 조회한다. */
export function runArrayItemType(type: ProblemFunctionParamType, index: number) {
  return type.elementType ?? type.positionTypes?.[index]
    ?? (type.repeatTypes?.length ? type.repeatTypes[index % type.repeatTypes.length] : undefined);
}

/** 문자열은 이스케이프하고 숫자는 원문 정밀도를 보존해 API용 JSON으로 변환한다. */
function encode(type: ProblemFunctionParamType, value: RunInputValue | undefined, path: string): string {
  const invalid = (message: string): never => { throw new Error(`${path}: ${message}`); };
  if (type.kind === "ARRAY" && value?.kind === "ARRAY") {
    if (type.positionTypes && value.items.length !== type.positionTypes.length) return invalid("항목 수를 확인해 주세요.");
    if (type.repeatTypes && value.items.length % type.repeatTypes.length !== 0) return invalid("항목 묶음을 확인해 주세요.");
    return `[${value.items.map((item, index) => {
      const child = runArrayItemType(type, index);
      if (!child) return invalid("배열의 항목 정보를 확인할 수 없습니다.");
      return encode(child, item, `${path}[${index + 1}]`);
    }).join(",")}]`;
  }
  if (type.kind === "OBJECT" && value?.kind === "OBJECT") {
    return `{${Object.entries(type.properties ?? {}).map(([name, child]) =>
      `${JSON.stringify(name)}:${encode(child, value.fields[name], `${path}.${name}`)}`).join(",")}}`;
  }
  if (value?.kind !== "SCALAR") return invalid("값을 입력해 주세요.");
  if (type.kind === "STRING") return JSON.stringify(value.text);
  if (type.kind === "BOOLEAN") {
    if (value.text !== "true" && value.text !== "false") return invalid("참 또는 거짓을 선택해 주세요.");
    return value.text;
  }
  const text = value.text.trim();
  if (type.kind === "INT32" || type.kind === "INT64") {
    if (!/^[+-]?\d+$/.test(text)) return invalid("정수를 입력해 주세요.");
    const number = BigInt(text);
    const bits = type.kind === "INT32" ? 31n : 63n;
    if (number < -(1n << bits) || number > (1n << bits) - 1n) return invalid("허용 범위 안의 정수를 입력해 주세요.");
    return number.toString();
  }
  if (type.kind === "FLOAT32" || type.kind === "FLOAT64") {
    const number = text.replace(/^\+/, "");
    if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(number) || !Number.isFinite(Number(number))) {
      return invalid("유효한 숫자를 입력해 주세요.");
    }
    const normalized = type.kind === "FLOAT32" ? Math.fround(Number(number)) : Number(number);
    if (!Number.isFinite(normalized) || normalized === 0 && /[1-9]/.test(number.split(/[eE]/)[0])) {
      return invalid("선언 타입의 표현 범위를 벗어났습니다.");
    }
    return number;
  }
  return invalid("입력 타입을 확인할 수 없습니다.");
}

/** 사용자 입력을 선언 타입에 맞춰 직렬화해 최대 다섯 직접 입력을 전송한다. */
export function runInputs(parameters: RunInputParameter[], rows: RunInputRow[], useSamples: boolean) {
  if (rows.length > 5 || (!useSamples && !rows.length)) {
    throw new Error("공개 예제 또는 직접 입력을 선택해 주세요. 직접 입력은 최대 5개입니다.");
  }
  return rows.map((row, index) => ({
    argumentTexts: Object.fromEntries(parameters.map(({ name, inputType }) => {
      if (!inputType) throw new Error(`${name}의 입력 정보를 확인할 수 없습니다.`);
      return [name, encode(inputType, row[name], `직접 입력 ${index + 1}의 ${name}`)];
    })),
  }));
}

/** 실행 시점의 선택적 기대값을 API에 보내지 않고 표시용 JSON으로 고정한다. */
export function runExpectedOutputs(type: ProblemFunctionParamType | undefined, rows: RunTestCase[]): (string | null)[] {
  return rows.map((row, index) => {
    if (!row.expectedOutput) return null;
    if (!type) throw new Error("기대 출력의 타입 정보를 확인할 수 없습니다.");
    return encode(type, row.expectedOutput, `직접 입력 ${index + 1}의 기대 출력`);
  });
}
