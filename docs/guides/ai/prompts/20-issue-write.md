# 20. Issue writing

## Purpose

Write an Issue body from a settled scope, and register it after the user approves.

## When to use

- After Step C of stage 10 settles the scope and decisions, once per Issue

## Inputs

- One Issue settled in stage 10
- The Feature, Task, or Bug template in `.github/ISSUE_TEMPLATE/`
- `docs/guides/git-github.md` > `1.3 Issue 관리`, `1.6 AI로 Issue·PR을 작성할 때`

## Steps

1. If the issue already exists (manually created):
    - Read title and body via GitHub MCP.
    - Compare against planned scope from 10-plan output.
    - Report all discrepancies (title mismatch, missing fields, placeholder text remaining).
    - Propose corrections. Wait for approval before proceeding.
    - Do NOT assume the body is correct just because it exists.
2. Follow `git-github.md` > `1.6 AI로 Issue·PR을 작성할 때`, and choose the template that fits the kind of work.
3. Put only the settled scope in the body. Do not write implementation choices that are not decided.
4. Put the reference document locations and prerequisite Issue numbers in the reference section.
5. Leave values not given in the inputs empty (due date, assignee, Issue numbers), and list them as questions outside the body.
6. Outside the body, give one line on why you chose the priority and size.
7. Output the body and wait for approval. Register it on GitHub only after the user says to register it.
8. Before posting, verify the issue body has no placeholders:
    - 🎯 작업 목적: not "여기에 작성"
    - ✅ 구현 내용: specific items, not "구현 상세 내용"
    - 🧭 중요도: one checkbox checked
    - 📏 예상 작업 규모: one checkbox checked
    - 📅 완료 기한: actual date in yyyy-mm-dd(요일) format
    - 이슈 확인 체크박스: leave ALL unchecked — author checks after posting, not AI

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
