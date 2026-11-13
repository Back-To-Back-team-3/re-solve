"use client";
import { Button } from "@/components/ui";

export function EditorRunInputs({ names, useSamples, rows, disabled, onSamples, onRows }: {
  names: string[]; useSamples: boolean; rows: Record<string, string>[]; disabled: boolean;
  onSamples: (value: boolean) => void; onRows: (value: Record<string, string>[]) => void;
}) {
  return <div className="max-h-[55vh] overflow-y-auto">
    <div className="stack-sm">
      <label><input type="checkbox" checked={useSamples} disabled={disabled}
        onChange={(event) => onSamples(event.target.checked)} /> 공개 예제 실행</label>
      {rows.map((row, index) => <fieldset key={index} className="stack-sm" disabled={disabled}>
        <legend>직접 입력 {index + 1}</legend>
        {names.map((name) => <label className="field" key={name}>
          {name} (JSON)
          <textarea className="input mono" aria-label={`직접 입력 ${index + 1} ${name}`}
            value={row[name] ?? ""} onChange={(event) => onRows(rows.map((item, i) =>
              i === index ? { ...item, [name]: event.target.value } : item))} />
        </label>)}
        <Button type="button" onClick={() => onRows(rows.filter((_, i) => i !== index))}>입력 {index + 1} 삭제</Button>
      </fieldset>)}
      <Button type="button" disabled={disabled || rows.length >= 5}
        onClick={() => onRows([...rows, Object.fromEntries(names.map((name) => [name, ""]))])}>직접 입력 추가</Button>
    </div>
  </div>;
}
