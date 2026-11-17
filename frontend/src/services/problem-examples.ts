import type { Nodes, Root, Table } from "mdast";
import type { Problem } from "@/services/types";

function cellText(node: Nodes): string {
  if ("value" in node) return node.value;
  return "children" in node ? node.children.map(cellText).join("") : "";
}

function sampleText(text: string): string {
  const value = text.trim();
  // 배열·객체의 표시 공백만 무시하고 문자열 내부와 큰 정수 원문은 보존한다.
  return /^[\[{]/.test(value)
    ? value.replace(/"(?:\\.|[^"\\])*"|\s+/g, (token) => token.startsWith('"') ? token : "")
    : value;
}

function hasOnlySampleText(node: Nodes): boolean {
  if (node.type === "text" || node.type === "inlineCode") return true;
  return ["table", "tableRow", "tableCell", "strong", "emphasis", "delete"].includes(node.type)
    && "children" in node && node.children.every(hasOnlySampleText);
}

function isSampleTable(table: Table, problem: Problem): boolean {
  // 이미지·링크·각주 등 텍스트 비교에 포함되지 않는 정보를 가진 표는 보존한다.
  if (!problem.samples.length || table.children.length < 2 || !hasOnlySampleText(table)) return false;
  const structured = problem.samples.every((sample) => sample.argumentTexts !== undefined);
  const columns = structured ? problem.functionSpec.parameters.map(({ name }) => name) : ["입력"];
  const [header, ...rows] = table.children;
  const headings = header.children.map(cellText).map((text) => text.trim());
  if (headings.length !== columns.length + 1
    || columns.some((name, index) => headings[index] !== name)
    || !["result", "return"].includes(headings[columns.length].toLowerCase())) return false;
  const samples = problem.samples.map((sample) => [
    ...(structured ? columns.map((name) => sample.argumentTexts?.[name] ?? "—") : [sample.input]),
    sample.expected,
  ].map(sampleText));
  return rows.every((row) => {
    const values = row.children.map(cellText).map(sampleText);
    return samples.some((sample) => sample.length === values.length
      && sample.every((value, index) => value === values[index]));
  });
}

/** 공개 테스트로 이미 표시한 예제 표만 제외하고 해설과 다른 표는 유지한다. */
export function remarkSampleTables(problem: Problem) {
  return (tree: Root) => {
    function removeDuplicates(node: Nodes) {
      if (!("children" in node)) return;
      for (let index = node.children.length - 1; index >= 0; index--) {
        const child = node.children[index];
        if (child.type === "table" && isSampleTable(child, problem)) node.children.splice(index, 1);
        else removeDuplicates(child);
      }
    }
    removeDuplicates(tree);
  };
}
