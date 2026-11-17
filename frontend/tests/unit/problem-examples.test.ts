import { describe, expect, it } from "vitest";
import type { Root, Table } from "mdast";
import { remarkSampleTables } from "@/services/problem-examples";
import type { Problem } from "@/services/types";

const problem: Problem = {
  id: "1", problemRevisionId: "2", title: "예제", difficulty: 1, tags: [],
  description: "", constraints: "", examples: "",
  functionSpec: { name: "solution", parameters: [{ name: "items", type: "ARRAY" }], returnType: "INT32" },
  starterCodes: { java: "", cpp: "", python3: "" },
  samples: [{ input: "[1,2]", argumentTexts: { items: "[1,2]" }, expected: "3" }],
};

function table(rows: string[][]): Table {
  return { type: "table", children: rows.map((cells) => ({ type: "tableRow", children: cells.map((value) => ({
    type: "tableCell", children: [{ type: "inlineCode", value }],
  })) })) };
}

describe("예시 Markdown 공개 테스트 중복 제거", () => {
  it("배열 표시 공백이 다른 공개 예제 표를 제거하고 앞뒤 해설을 보존한다", () => {
    const tree: Root = { type: "root", children: [
      { type: "paragraph", children: [{ type: "text", value: "풀이 과정" }] },
      table([["items", "result"], ["[1, 2]", "3"]]),
      { type: "paragraph", children: [{ type: "text", value: "해설" }] },
    ] };
    remarkSampleTables(problem)(tree);
    expect(tree.children.map((node) => node.type)).toEqual(["paragraph", "paragraph"]);
    expect(tree.children).toContainEqual({ type: "paragraph", children: [{ type: "text", value: "해설" }] });
  });

  it.each([
    [["단계", "합"], ["[1,2]", "3"]],
    [["items", "result"], ["[1,2]", "4"]],
    [["items", "result"], ["[1,2]", "3"], ["[3,4]", "7"]],
  ])("해설 표나 공개 테스트에 없는 행이 포함된 표는 보존한다: %j", (...rows) => {
    const original = table(rows);
    const tree: Root = { type: "root", children: [original] };
    remarkSampleTables(problem)(tree);
    expect(tree.children).toEqual([original]);
  });

  it("공개 테스트가 없으면 Markdown 예제 표를 보존한다", () => {
    const original = table([["items", "result"], ["[1,2]", "3"]]);
    const tree: Root = { type: "root", children: [original] };
    remarkSampleTables({ ...problem, samples: [] })(tree);
    expect(tree.children).toEqual([original]);
  });

  it.each([
    ["[9007199254740993]", "[9007199254740992]"],
    ['["a b"]', '["ab"]'],
  ])("큰 정수와 문자열 내부 공백이 다른 표는 보존한다: %s", (sample, example) => {
    const original = table([["items", "result"], [example, "3"]]);
    const tree: Root = { type: "root", children: [original] };
    remarkSampleTables({ ...problem, samples: [{ input: sample, argumentTexts: { items: sample }, expected: "3" }] })(tree);
    expect(tree.children).toEqual([original]);
  });
});
