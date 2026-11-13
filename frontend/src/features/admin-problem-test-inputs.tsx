"use client";
import { Button } from "@/components/ui";
import { EditorRunValue } from "@/features/editor-run-value";
import { parseProblemFunction } from "@/services/problem-authoring";
import { newProblemTest, type ProblemTestInput, type ProblemTestInputs } from "@/services/problem-test-inputs";

export function AdminProblemTestInputs({ functionSpec, value, onChange }: {
  functionSpec: string; value: ProblemTestInputs; onChange: (value: ProblemTestInputs) => void;
}) {
  let fn;
  try { fn = parseProblemFunction(functionSpec); }
  catch { return <p role="alert">테스트를 입력하려면 올바른 함수 명세를 먼저 작성해 주세요. 기존 입력은 유지됩니다.</p>; }
  if (value.definition !== JSON.stringify(fn)) return <section className="card stack-sm">
    <p role="alert">함수 명세가 변경되었습니다. 기존 테스트 입력은 유지하고 있으며, 새 명세로 입력하려면 초기화해 주세요.</p>
    <Button type="button" onClick={() => onChange({ definition: JSON.stringify(fn), rows: [newProblemTest(fn, true), newProblemTest(fn)] })}>
      새 함수 명세로 테스트 초기화
    </Button>
  </section>;
  const update = (index: number, patch: Partial<ProblemTestInput>) => onChange({ ...value,
    rows: value.rows.map((test, i) => i === index ? { ...test, ...patch } : test) });
  return <section className="stack-sm" aria-label="공개·숨김 테스트">
    <h3>공개·숨김 테스트</h3>
    <p className="muted">매개변수별 값과 기대 출력을 입력하세요. 공개·숨김 테스트가 각각 1개 이상 필요하며 최대 50개까지 등록할 수 있어요.</p>
    <div className="overflow-x-auto">
      <table>
        <thead><tr><th scope="col">번호</th><th scope="col">공개 여부</th>
          {fn.parameters.map((param) => <th scope="col" key={param.name}>{param.name}</th>)}
          <th scope="col">기대 출력</th><th scope="col">관리</th></tr></thead>
        <tbody>{value.rows.map((test, index) => <tr key={index}>
          <th scope="row">{index + 1}</th>
          <td><select className="input" aria-label={`테스트 ${index + 1} 공개 여부`} value={test.isSample ? "sample" : "hidden"}
            onChange={(event) => update(index, { isSample: event.target.value === "sample" })}>
            <option value="sample">공개</option><option value="hidden">숨김</option>
          </select></td>
          {fn.parameters.map((param) => <td key={param.name} className="min-w-40 align-top">
            <EditorRunValue name={`테스트 ${index + 1} ${param.name}`} hideLabel type={param.type} value={test.arguments[param.name]}
              onChange={(next) => update(index, { arguments: { ...test.arguments, [param.name]: next } })} />
          </td>)}
          <td className="min-w-40 align-top"><EditorRunValue name={`테스트 ${index + 1} 기대 출력`} hideLabel
            type={fn.returnType} value={test.expected} onChange={(next) => update(index, { expected: next })} /></td>
          <td><Button type="button" className="small" disabled={value.rows.length <= 1}
            onClick={() => onChange({ ...value, rows: value.rows.filter((_, i) => i !== index) })}>테스트 {index + 1} 삭제</Button></td>
        </tr>)}</tbody>
      </table>
    </div>
    <Button type="button" disabled={value.rows.length >= 50}
      onClick={() => onChange({ ...value, rows: [...value.rows, newProblemTest(fn)] })}>테스트 추가 ({value.rows.length}/50)</Button>
  </section>;
}
