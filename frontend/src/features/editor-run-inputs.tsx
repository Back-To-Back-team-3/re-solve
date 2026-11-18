"use client";
import { useId } from "react";
import { Button } from "@/components/ui";
import { EditorRunValue, runValueTypeLabel } from "@/features/editor-run-value";
import type { ProblemFunctionParamType } from "@/services/types";
import { createRunText, type RunInputParameter, type RunTestCase } from "@/services/run-inputs";

export function EditorRunInputs({ parameters, returnType, rows, disabled, onRows, storageError, maxCases = 5, allowExpectations = true }: {
  maxCases?: number; allowExpectations?: boolean; storageError?: string;
  parameters: RunInputParameter[]; returnType?: ProblemFunctionParamType; rows: RunTestCase[]; disabled: boolean;
  onRows: (value: RunTestCase[]) => void;
}) {
  const id = useId();
  const supported = parameters.every((parameter) => parameter.inputType);
  return <div className="min-w-0 space-y-3">
    <div className="max-h-[55vh] min-w-0 overflow-y-auto overscroll-contain pr-1">
      <fieldset className="stack-sm m-0 min-w-0 border-0 p-0" disabled={disabled}>
        <small className="muted">값을 텍스트로 입력하세요. 문자열은 따옴표 없이, 배열·객체는 JSON으로 입력해 주세요.</small>
        <div className="min-w-0 overflow-x-auto" role="region" aria-label="테스트 케이스 표 스크롤" tabIndex={0}>
          <table className="w-max min-w-full table-fixed" aria-label="직접 입력 테스트 케이스">
            <thead><tr>
              <th scope="col" className="w-16 min-w-16 whitespace-nowrap">번호</th>
              {parameters.map(({ name, inputType }, index) => <th scope="col" key={name} className="w-60 min-w-60 [overflow-wrap:anywhere]">
                {name}{inputType && <small id={`${id}-type-${index}`} className="block font-normal opacity-80">{runValueTypeLabel(inputType)}</small>}
              </th>)}
              {allowExpectations && <th scope="col" className="w-60 min-w-60">result <small>(선택)</small>
                {returnType && <small id={`${id}-return-type`} className="block font-normal opacity-80">{runValueTypeLabel(returnType)}</small>}
              </th>}
              <th scope="col" className="w-24 min-w-24 whitespace-nowrap">관리</th>
            </tr></thead>
            <tbody>{rows.map((row, index) => <tr key={index}>
              <th scope="row" className="align-top">{index + 1}</th>
              {parameters.map(({ name, inputType }, parameterIndex) => <td key={name} className="w-60 min-w-60 align-top">
                {inputType && <EditorRunValue name={`직접 입력 ${index + 1} ${name}`} type={inputType} hintId={`${id}-type-${parameterIndex}`}
                  value={row.arguments[name] ?? createRunText(inputType)}
                  onChange={(value) => onRows(rows.map((item, i) => i === index ? { ...item, arguments: { ...item.arguments, [name]: value } } : item))} />}
              </td>)}
              {allowExpectations && <td className="w-60 min-w-60 align-top">
                {returnType && <EditorRunValue name={`직접 입력 ${index + 1} 기대 출력`} optional hintId={`${id}-return-type`}
                  type={returnType} value={row.expectedOutput ?? { kind: "TEXT", text: "" }}
                  onChange={(value) => onRows(rows.map((item, i) => i === index
                    ? { ...item, expectedOutput: value.kind === "TEXT" && value.text === "" ? undefined : value } : item))} />}
              </td>}
              <td className="min-w-24 align-top"><Button type="button" className="small whitespace-nowrap" aria-label={`입력 ${index + 1} 삭제`}
                onClick={() => onRows(rows.filter((_, i) => i !== index))}>삭제</Button></td>
            </tr>)}</tbody>
          </table>
        </div>
        {allowExpectations && <small className="muted">기대 출력은 빈칸으로 둘 수 있습니다. 입력한 값은 결과와 함께 표시하며, 자동으로 통과·실패를 판정하지 않습니다.</small>}
        {!supported && <p className="error-text">직접 입력에 필요한 함수 명세를 확인할 수 없습니다.</p>}
        <Button type="button" disabled={disabled || !supported || rows.length >= maxCases}
          onClick={() => onRows([...rows, { arguments: Object.fromEntries(parameters.flatMap(({ name, inputType }) =>
            inputType ? [[name, createRunText(inputType)]] : [])) }])}>직접 입력 추가</Button>
      </fieldset>
    </div>
    {storageError ? <p role="alert" className="error-text">{storageError}</p>
      : <small className="muted">입력 내용은 이 브라우저에 자동 저장됩니다.</small>}
  </div>;
}
