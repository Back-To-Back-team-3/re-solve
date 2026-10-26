# 00. Session start

## Purpose

Confirm the user, owner group, Issue, and branch, and choose the decision records and reference documents to read for the work.

## When to use

- At the start of a new AI session
- When switching to another Issue in the same session

## Inputs

- `AGENTS.md` (loaded automatically)
- The Issue body: Claude Code reads it through GitHub MCP. With other tools, the user pastes it.

## Steps

Report results only. Do not write code in this stage.

1. Go through `AGENTS.md` > `1. Before starting work` in order.
2. Summarize the Issue's purpose, scope, and due date. Mark missing values as empty.
3. Check the current branch and `git status`, and whether the branch name follows `docs/guides/git-github.md` > `1.1 브랜치 전략`.
4. Find the decision records in `docs/records/decisions/` related to the work.
5. Choose the reference documents and sections to read from `AGENTS.md` > `4. Reference documents`.
6. If a decision record and a reference document say different things, list each difference as `document > section > phrase`.
7. List the scope items that have no grounds in the documents as questions.
8. If `docs/records/progress/{owner}.md` exists, read it and add the current cycle and recent PR status to the 작업 준비 보고 table. If a PR merged since the last update exists, ask the user whether to update the file now or after the session ends.

## Output format

### 작업 준비 보고
| 항목 | 확인 결과 |
| --- | --- |
| 작업자·담당 | |
| 수정 가능한 서비스 | |
| Issue | 제목, 완료 기한(없으면 "비어 있음") |
| 브랜치 | 이름, 규칙 일치 여부 |
| 관련 결정 기록 | 파일명 |
| 읽을 기준 문서 | 문서 > 섹션 |

### 결정 기록과 기준 문서의 차이
- 없음 / 문서 > 섹션 > 찾을 문구: 차이

### 확인이 필요한 항목
1.

## Do not

- Fill in the Issue scope, due date, or assignee by guessing.
- Create or switch branches without approval.

## Human gate

- The user reviews the report and says to proceed before the next stage starts.
- The user answers the items under 확인이 필요한 항목, or confirms them with the owner, before proceeding.

## Tool differences

| Claude Code | Codex, Gemini CLI |
| --- | --- |
| Reads the Issue through MCP | The user pastes the Issue body |
