"use client";
import { Fragment, useState } from "react";
import { Button } from "@/components/ui";
import type { ProblemFunctionParamType, ProblemWriteContent } from "@/services/types";

const types = [
  ["INT32", "정수 (32비트)"], ["INT64", "큰 정수 (64비트)"],
  ["FLOAT32", "실수 (32비트)"], ["FLOAT64", "실수 (64비트)"],
  ["STRING", "문자열"], ["BOOLEAN", "참·거짓"], ["ARRAY", "배열"], ["OBJECT", "객체"],
];

/** 선언 타입을 바꾸면 이전 타입의 하위 구조를 남기지 않는다. */
function initialType(kind: string): ProblemFunctionParamType {
  if (kind === "ARRAY") return { kind, elementType: { kind: "INT32" } };
  if (kind === "OBJECT") return { kind, properties: {} };
  return { kind };
}

function TypeInput({ name, value, onChange, hideLabel = false, label, inlineOnly = false }: {
  name: string; value: ProblemFunctionParamType; onChange: (value: ProblemFunctionParamType) => void; hideLabel?: boolean; label?: string; inlineOnly?: boolean;
}) {
  const mode = value.positionTypes ? "positionTypes" : value.repeatTypes ? "repeatTypes" : "elementType";
  return <div className="min-w-0 max-w-full space-y-2">
    <div className="flex flex-wrap items-center gap-2">
    <label className="flex w-56 max-w-full items-center gap-2"><span className={hideLabel ? "sr-only" : "shrink-0 text-sm"}>{label ?? `${name} 타입`}</span><select className="input min-w-0 flex-1" aria-label={`${name} 타입`} value={value.kind}
      onChange={(event) => onChange(initialType(event.target.value))}>
      {types.map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}
    </select></label>
    {value.kind === "ARRAY" && <label className="w-48 max-w-full"><span className="sr-only">{name} 배열 구성</span>
      <select className="input" aria-label={`${name} 배열 구성`} value={mode} onChange={(event) => {
        const selected = event.target.value;
        onChange(selected === "elementType" ? initialType("ARRAY") : { kind: "ARRAY", [selected]: [{ kind: "INT32" }] });
      }}>
        <option value="elementType">동일 타입 목록</option>
        <option value="positionTypes">위치별 고정 타입</option>
        <option value="repeatTypes">타입 패턴 반복</option>
      </select></label>}
    </div>
    {!inlineOnly && <TypeDetails key={value.kind} name={name} value={value} onChange={onChange} />}
  </div>;
}

function TypeDetails({ name, value, onChange }: {
  name: string; value: ProblemFunctionParamType; onChange: (value: ProblemFunctionParamType) => void;
}) {
  const [fieldName, setFieldName] = useState("");
  const [error, setError] = useState("");
  const mode = value.positionTypes ? "positionTypes" : value.repeatTypes ? "repeatTypes" : "elementType";
  const positions = value.positionTypes ?? value.repeatTypes ?? [];
  return <>
    {value.kind === "ARRAY" && <fieldset className="ml-2 min-w-0 space-y-2 border-l-2 pl-3">
      <legend className="sr-only">{name} 하위 타입</legend>
      {mode === "elementType" && value.elementType && <TypeInput name={`${name} 원소`} label="원소" value={value.elementType}
        onChange={(next) => onChange({ kind: "ARRAY", elementType: next })} />}
      {mode !== "elementType" && <>
        {positions.map((type, index) => <div className="flex flex-wrap items-start gap-2" key={index}>
          <TypeInput name={`${name} 항목 ${index + 1}`} label={`[${index + 1}]`} value={type}
            onChange={(next) => onChange({ kind: "ARRAY", [mode]: positions.map((item, i) => i === index ? next : item) })} />
          <Button type="button" className="small" aria-label={`${name} 항목 ${index + 1} 삭제`} disabled={mode === "repeatTypes" && positions.length === 1}
            onClick={() => onChange({ kind: "ARRAY", [mode]: positions.filter((_, i) => i !== index) })}>삭제</Button>
        </div>)}
        <Button type="button" className="small" aria-label={`${name} 타입 항목 추가`} onClick={() => onChange({ kind: "ARRAY", [mode]: [...positions, { kind: "INT32" }] })}>
          타입 항목 추가
        </Button>
      </>}
    </fieldset>}
    {value.kind === "OBJECT" && <fieldset className="ml-2 min-w-0 space-y-2 border-l-2 pl-3">
      <legend className="sr-only">{name} 객체 필드</legend>
      {Object.entries(value.properties ?? {}).map(([key, type]) => <div className="flex flex-wrap items-start gap-2" key={key}>
        <TypeInput name={`${name}.${key}`} label={key} value={type}
          onChange={(next) => onChange({ kind: "OBJECT", properties: { ...value.properties, [key]: next } })} />
        <Button type="button" className="small" aria-label={`${name}.${key} 필드 삭제`} onClick={() => onChange({ kind: "OBJECT",
          properties: Object.fromEntries(Object.entries(value.properties ?? {}).filter(([field]) => field !== key)) })}>
          삭제
        </Button>
      </div>)}
      <div className="flex flex-wrap items-center gap-2">
      <label className="w-48 max-w-full"><span className="sr-only">새 필드 이름</span><input className="input" placeholder="새 필드 이름" aria-label={`${name} 새 필드 이름`} value={fieldName}
        onChange={(event) => { setFieldName(event.target.value); setError(""); }} /></label>
      <Button type="button" className="small" aria-label={`${name} 필드 추가`} onClick={() => {
        const key = fieldName.trim();
        if (!key || Object.hasOwn(value.properties ?? {}, key)) { setError("비어 있지 않은 중복 없는 필드 이름을 입력해 주세요."); return; }
        onChange({ kind: "OBJECT", properties: { ...value.properties, [key]: { kind: "INT32" } } }); setFieldName(""); setError("");
      }}>필드 추가</Button></div>
      {error && <p className="error-text" role="alert">{error}</p>}
    </fieldset>}
  </>;
}

export function AdminProblemFunction({ value, onChange }: {
  value: ProblemWriteContent["functionSpec"]; onChange: (value: ProblemWriteContent["functionSpec"]) => void;
}) {
  const update = (parameters: typeof value.parameters) => onChange({ ...value, parameters });
  return <section className="stack-sm" aria-label="함수 명세">
    <h3>함수 명세</h3>
    <p>함수 이름은 <code>solution</code>입니다. 매개변수는 아래 순서대로 전달됩니다.</p>
    <div className="overflow-x-auto"><table className="w-full min-w-[40rem] table-fixed">
      <thead><tr><th scope="col" className="w-16">순서</th><th scope="col" className="w-32">이름</th><th scope="col">타입</th><th scope="col" className="w-36">관리</th></tr></thead>
      <tbody>{value.parameters.map((param, index) => <Fragment key={index}><tr>
        <th scope="row" className="align-top" rowSpan={param.type.kind === "ARRAY" || param.type.kind === "OBJECT" ? 2 : 1}>{index + 1}</th>
        <td className="align-top"><input className="input" aria-label={`매개변수 ${index + 1} 이름`} value={param.name}
          onChange={(event) => update(value.parameters.map((item, i) => i === index ? { ...item, name: event.target.value } : item))} /></td>
        <td className="min-w-56 align-top"><TypeInput name={`매개변수 ${index + 1}`} value={param.type} hideLabel inlineOnly
          onChange={(next) => update(value.parameters.map((item, i) => i === index ? { ...item, type: next } : item))} /></td>
        <td className="align-top"><div className="row wrap">
          <Button type="button" className="small" aria-label={`매개변수 ${index + 1} 위로`} disabled={index === 0} onClick={() => {
            const rows = [...value.parameters]; [rows[index - 1], rows[index]] = [rows[index], rows[index - 1]]; update(rows);
          }}>위로</Button>
          <Button type="button" className="small" aria-label={`매개변수 ${index + 1} 삭제`}
            onClick={() => update(value.parameters.filter((_, i) => i !== index))}>삭제</Button>
        </div></td>
      </tr>
        {(param.type.kind === "ARRAY" || param.type.kind === "OBJECT") && <tr><td colSpan={3}>
          <TypeDetails key={param.type.kind} name={`매개변수 ${index + 1}`} value={param.type}
            onChange={(next) => update(value.parameters.map((item, i) => i === index ? { ...item, type: next } : item))} />
        </td></tr>}
      </Fragment>)}
        <tr><th scope="row" className="align-top" rowSpan={value.returnType.kind === "ARRAY" || value.returnType.kind === "OBJECT" ? 2 : 1}>반환</th><td>—</td><td className="min-w-56 align-top">
          <TypeInput name="반환" value={value.returnType} hideLabel inlineOnly onChange={(next) => onChange({ ...value, returnType: next })} />
        </td><td>—</td></tr>
        {(value.returnType.kind === "ARRAY" || value.returnType.kind === "OBJECT") && <tr><td colSpan={3}>
          <TypeDetails key={value.returnType.kind} name="반환" value={value.returnType} onChange={(next) => onChange({ ...value, returnType: next })} />
        </td></tr>}
      </tbody>
    </table></div>
    <Button type="button" onClick={() => {
      let number = value.parameters.length + 1;
      while (value.parameters.some((param) => param.name === `arg${number}`)) number++;
      update([...value.parameters, { name: `arg${number}`, type: { kind: "INT32" } }]);
    }}>매개변수 추가</Button>
  </section>;
}
