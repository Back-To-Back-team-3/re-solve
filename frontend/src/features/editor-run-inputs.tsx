"use client";
import { Button } from "@/components/ui";
import { EditorRunValue } from "@/features/editor-run-value";
import type { ProblemFunctionParamType } from "@/services/types";
import { createRunInput, type RunInputParameter, type RunTestCase } from "@/services/run-inputs";

export function EditorRunInputs({ parameters, returnType, useSamples, rows, disabled, onSamples, onRows }: {
  parameters: RunInputParameter[]; returnType?: ProblemFunctionParamType; useSamples: boolean; rows: RunTestCase[]; disabled: boolean;
  onSamples: (value: boolean) => void; onRows: (value: RunTestCase[]) => void;
}) {
  const supported = parameters.every((parameter) => parameter.inputType);
  return <div className="max-h-[55vh] overflow-y-auto">
    <fieldset className="stack-sm m-0 min-w-0 border-0 p-0" disabled={disabled}>
      <label><input type="checkbox" checked={useSamples} disabled={disabled}
        onChange={(event) => onSamples(event.target.checked)} /> 공개 예제 실행</label>
      <small className="muted">값만 입력하세요. 목록은 항목을 추가하고, 문자열은 따옴표 없이 입력하면 돼요.</small>
      {rows.length > 0 && <div className="table-wrap">
        <table className={parameters.length <= 2 ? "min-w-0! w-full table-fixed" : "min-w-full"} aria-label="직접 입력 테스트 케이스">
          <thead><tr>
            <th scope="col" className="w-10">#</th>
            {parameters.map(({ name }) => <th scope="col" key={name}>{name}</th>)}
            <th scope="col">기대 출력 <small>(선택)</small></th>
            <th scope="col" className="w-16">관리</th>
          </tr></thead>
          <tbody>{rows.map((row, index) => <tr key={index}>
            <th scope="row">{index + 1}</th>
            {parameters.map(({ name, inputType }) => <td className="align-top" key={name}>
              {inputType && <EditorRunValue hideLabel
                name={`직접 입력 ${index + 1} ${name}`} type={inputType} value={row.arguments[name]}
                onChange={(value) => onRows(rows.map((item, i) => i === index ? { ...item, arguments: { ...item.arguments, [name]: value } } : item))} />}
            </td>)}
            <td className="align-top">
              <label className="row">
                <input type="checkbox" aria-label={`직접 입력 ${index + 1} 기대 출력 사용`}
                  checked={row.expectedOutput !== undefined} disabled={!returnType}
                  onChange={(event) => onRows(rows.map((item, i) => i === index
                    ? { ...item, expectedOutput: event.target.checked && returnType ? createRunInput(returnType) : undefined } : item))} />
                입력
              </label>
              {returnType && row.expectedOutput && <EditorRunValue hideLabel
                name={`직접 입력 ${index + 1} 기대 출력`} type={returnType} value={row.expectedOutput}
                onChange={(expectedOutput) => onRows(rows.map((item, i) => i === index ? { ...item, expectedOutput } : item))} />}
            </td>
            <td className="align-top"><Button type="button" className="small" aria-label={`입력 ${index + 1} 삭제`}
              onClick={() => onRows(rows.filter((_, i) => i !== index))}>삭제</Button></td>
          </tr>)}</tbody>
        </table>
      </div>}
      <small className="muted">기대 출력은 결과와 함께 표시하는 참고값이며, 자동으로 통과·실패를 판정하지 않습니다.</small>
      {!supported && <p className="error-text">직접 입력에 필요한 함수 명세를 확인할 수 없습니다.</p>}
      <Button type="button" disabled={disabled || !supported || rows.length >= 5}
        onClick={() => onRows([...rows, { arguments: Object.fromEntries(parameters.flatMap(({ name, inputType }) =>
          inputType ? [[name, createRunInput(inputType)]] : [])) }])}>직접 입력 추가</Button>
    </fieldset>
  </div>;
}
