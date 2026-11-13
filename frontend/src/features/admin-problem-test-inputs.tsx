"use client";
import { Button } from "@/components/ui";
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
    <Button type="button" onClick={() => onChange({ definition: JSON.stringify(fn), rows: [newProblemTest(true), newProblemTest()] })}>
      새 함수 명세로 테스트 초기화
    </Button>
  </section>;
  const update = (index: number, patch: Partial<ProblemTestInput>) => onChange({ ...value,
    rows: value.rows.map((test, i) => i === index ? { ...test, ...patch } : test) });
  return <section className="stack-sm" aria-label="공개·숨김 테스트">
    <h3>공개·숨김 테스트</h3>
    <p className="muted">입력값은 매개변수 이름을 키로 하는 JSON 객체로 작성하세요. 공개·숨김 각각 1개 이상, 최대 50개까지 등록할 수 있어요.</p>
    <p className="muted">매개변수: {fn.parameters.map((param) => param.name).join(", ") || "없음 (입력값에 {} 사용)"}. 문자열은 큰따옴표로 감싸고 배열·객체는 그대로 입력하세요.</p>
    <p className="muted">기대 출력은 반환값 그대로 입력하세요. 정수는 <code>32</code>, 배열은 <code>[1, 2]</code>, 문자열은 <code>{'"hello"'}</code>처럼 작성하며 객체를 반환할 때만 <code>{"{...}"}</code>를 사용합니다.</p>
    <div className="overflow-x-auto">
      <table>
        <thead><tr><th scope="col" className="whitespace-nowrap">번호</th><th scope="col" className="min-w-28 whitespace-nowrap">공개 여부</th>
          <th scope="col">입력값 (JSON)</th><th scope="col">기대 출력 (JSON)</th><th scope="col">관리</th></tr></thead>
        <tbody>{value.rows.map((test, index) => <tr key={index}>
          <th scope="row">{index + 1}</th>
          <td className="min-w-28 align-top"><select className="input w-24 min-w-24" aria-label={`테스트 ${index + 1} 공개 여부`} value={test.isSample ? "sample" : "hidden"}
            onChange={(event) => update(index, { isSample: event.target.value === "sample" })}>
            <option value="sample">공개</option><option value="hidden">숨김</option>
          </select></td>
          <td className="min-w-72 align-top"><textarea className="input mono" rows={5} spellCheck={false}
            aria-label={`테스트 ${index + 1} 입력값 JSON`} value={test.argumentsText}
            onChange={(event) => update(index, { argumentsText: event.target.value })} /></td>
          <td className="min-w-48 align-top"><textarea className="input mono" rows={5} spellCheck={false}
            aria-label={`테스트 ${index + 1} 기대 출력 JSON`} value={test.expectedText}
            onChange={(event) => update(index, { expectedText: event.target.value })} /></td>
          <td><Button type="button" className="small" disabled={value.rows.length <= 1}
            onClick={() => onChange({ ...value, rows: value.rows.filter((_, i) => i !== index) })}>테스트 {index + 1} 삭제</Button></td>
        </tr>)}</tbody>
      </table>
    </div>
    <Button type="button" disabled={value.rows.length >= 50}
      onClick={() => onChange({ ...value, rows: [...value.rows, newProblemTest()] })}>테스트 추가 ({value.rows.length}/50)</Button>
  </section>;
}
