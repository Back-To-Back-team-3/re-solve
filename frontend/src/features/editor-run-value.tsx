"use client";
import { Button } from "@/components/ui";
import { createRunInput, runArrayItemType, type RunInputValue } from "@/services/run-inputs";
import type { ProblemFunctionParamType } from "@/services/types";

export function EditorRunValue({ name, type, value, onChange, hideLabel = false }: {
  name: string; hideLabel?: boolean; type: ProblemFunctionParamType; value: RunInputValue; onChange: (value: RunInputValue) => void;
}) {
  if (type.kind === "ARRAY" && value.kind === "ARRAY") {
    const step = type.repeatTypes?.length ?? 1;
    const additions = type.repeatTypes ?? (type.elementType ? [type.elementType] : []);
    return <fieldset className="card stack-sm min-w-0">
      <legend className={hideLabel ? "sr-only" : undefined}>{name} · 목록</legend>
      {value.items.length === 0 && <small className="muted">아직 항목이 없어요. 빈 목록으로 실행할 수도 있어요.</small>}
      {value.items.map((item, index) => {
        const itemType = runArrayItemType(type, index);
        return itemType && <div className="stack-sm" key={index}>
          <EditorRunValue name={`${name}[${index + 1}]`} type={itemType} value={item}
            onChange={(next) => onChange({ kind: "ARRAY", items: value.items.map((current, i) => i === index ? next : current) })} />
          {!type.positionTypes && (index + 1) % step === 0 && <Button type="button" className="small"
            onClick={() => onChange({ kind: "ARRAY", items: value.items.filter((_, i) => i < index + 1 - step || i > index) })}>
            {type.repeatTypes ? `${name} 묶음 ${(index + 1) / step} 삭제` : `${name} 항목 ${index + 1} 삭제`}
          </Button>}
        </div>;
      })}
      {!type.positionTypes && additions.length > 0 && <Button type="button"
        onClick={() => onChange({ kind: "ARRAY", items: [...value.items, ...additions.map(createRunInput)] })}>
        {name} {type.repeatTypes ? "묶음" : "항목"} 추가
      </Button>}
    </fieldset>;
  }
  if (type.kind === "OBJECT" && value.kind === "OBJECT") {
    return <fieldset className="card stack-sm min-w-0">
      <legend className={hideLabel ? "sr-only" : undefined}>{name} · 항목별 입력</legend>
      {Object.entries(type.properties ?? {}).map(([key, child]) => <EditorRunValue key={key}
        name={`${name}.${key}`} type={child} value={value.fields[key]}
        onChange={(next) => onChange({ kind: "OBJECT", fields: { ...value.fields, [key]: next } })} />)}
    </fieldset>;
  }
  if (value.kind !== "SCALAR") return null;
  const update = (text: string) => onChange({ kind: "SCALAR", text });
  if (type.kind === "BOOLEAN") {
    return <label className="field"><span className={hideLabel ? "sr-only" : undefined}>{name}</span>
      <select className="input min-w-0" aria-label={name} value={value.text} onChange={(event) => update(event.target.value)}>
        <option value="false">거짓 (false)</option><option value="true">참 (true)</option>
      </select>
    </label>;
  }
  if (type.kind === "STRING") {
    return <label className="field"><span className={hideLabel ? "sr-only" : undefined}>{name}</span>
      <textarea className="input min-w-0" aria-label={name} placeholder="문자열을 그대로 입력하세요 (따옴표 불필요)"
        value={value.text} onChange={(event) => update(event.target.value)} />
    </label>;
  }
  const integer = type.kind === "INT32" || type.kind === "INT64";
  return <label className="field"><span className={hideLabel ? "sr-only" : undefined}>{name}</span>
    <input className="input mono min-w-0" aria-label={name} type="text" inputMode={integer ? "numeric" : "decimal"}
      placeholder={integer ? "정수 입력 · 예: 123" : "숫자 입력 · 예: 1.5"}
      value={value.text} onChange={(event) => update(event.target.value)} />
  </label>;
}
