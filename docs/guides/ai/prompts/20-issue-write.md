# 20. Issue writing

## Purpose

Write an Issue body from a settled scope, and register it after the user approves.

## When to use

- After Step B of stage 10 settles the scope and decisions, once per Issue

## Inputs

- One Issue settled in stage 10
- The Feature, Task, or Bug template in `.github/ISSUE_TEMPLATE/`
- `docs/guides/git-github.md` > `1.3 Issue 관리`, `1.6 AI로 Issue·PR을 작성할 때`

## Steps

1. Follow `git-github.md` > `1.6 AI로 Issue·PR을 작성할 때`, and choose the template that fits the kind of work.
2. Put only the settled scope in the body. Do not write implementation choices that are not decided.
3. Put the reference document locations and prerequisite Issue numbers in the reference section.
4. Leave values not given in the inputs empty (due date, assignee, Issue numbers), and list them as questions outside the body.
5. Outside the body, give one line on why you chose the priority and size.
6. Output the body and wait for approval. Register it on GitHub only after the user says to register it.

## Output format

- Issue 제목
- Issue 본문 (템플릿 형식, 안내 주석 제거)
- 본문 밖: 템플릿 선택 이유, 중요도·규모 판단 이유, 비워 둔 값과 질문

## Do not

- Copy template example sentences as facts.
- Fill empty values by guessing.
- Register without approval.

## Human gate

- The user reviews the body, fills the empty values, and says to register it.
- After registration, the user checks the milestone, assignee, and labels.

## Tool differences

| Claude Code | Codex, Gemini CLI |
| --- | --- |
| Registers through MCP after approval and reports the new number | Outputs the body; the user registers it |
