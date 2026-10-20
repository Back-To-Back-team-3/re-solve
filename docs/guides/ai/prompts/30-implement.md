# 30. Implementation

## Purpose

Implement a settled Issue scope one commit at a time, with approval for each commit.

## When to use

- After the user approves the stage 00 report

## Inputs

- The Issue number and settled scope
- The decision records and reference documents chosen in stage 00
- `docs/guides/convention-build-manual.md` > `4. 평소 개발 시 — 매 커밋 전 루틴`

## Steps — A. Plan

1. Output only the implementation plan first.
    - Order commits so that each one compiles on its own: lower layers first. Put tests in the same commit as the code they test.
    - Follow `docs/guides/convention-build-manual.md` > `6. 코드 구조` for packages, class names, and layers.
    - For each commit, list the files and the commit message (`git-github.md` > `1.2 커밋·Issue·PR 제목`, with the Issue's type and domain).
    - Give the grounds for each decision as `document > section > phrase`.
2. List the following as option tables only. Do not decide them.
    - Event publishing versus a direct call, transaction boundaries, splitting a service
    - Error cases not in the specification, values not in the documents
3. If a decision record and a reference document differ, follow the decision record and list the difference.
4. If the work cannot be done without a contract change (API, event, ERD, error code, policy), stop planning and propose the document change.
5. Output the plan and wait for approval.

## Steps — B. Each commit

1. Write or change only the files in the plan for commit {n}. If another file is needed, say so and wait for approval.
2. Run the commands in `convention-build-manual.md` > `4. 평소 개발 시 — 매 커밋 전 루틴` and show the results.
3. Show `git diff --stat` and the commit message, and wait for approval.
4. Commit only after the user says to commit. Do not push.

## Output format

```markdown
### 커밋 계획
| # | 레이어 | 파일 | 커밋 메시지 | 근거 |
| --- | --- | --- | --- | --- |

### 결정 필요
| 판단 | 선택지 | 장점 | 단점 | 근거 문서 |
| --- | --- | --- | --- | --- |

### 결정 기록과 기준 문서의 차이
-
```

## Do not

- Change files outside the plan without approval.
- Use a response format, status code, or error code that is not in the specification.
- Decide values that are not in the documents.
- Change files owned by another owner group without that owner's confirmation (`AGENTS.md` > `3. Scope and prohibitions`).
- Commit while compilation fails.
- Push.

## Human gate

- Approve the plan, decide the items under 결정 필요, and create a document Issue when a contract change is needed.
- Review the diff of each commit and say to commit.

## Tool differences

None
