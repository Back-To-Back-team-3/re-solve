# 10. Plan and break down

## Purpose

Group the owner group's features into categories (Step B), check the cycle table first (Step A), then split one approved category into Issues and separate what people must decide (Step C).

## When to use

- Step A: before every issue, to check the cycle table
- Step B: at the start of a milestone, or when scope or priority changes
- Step C: once for each category approved in Step B

## Inputs

- `docs/dev/planning/planning.md` > `2. 작업 구성`, `3.2 요구사항 명세`
- Milestone name and due date, from the user
- `docs/dev/specifications/event-contract.md` > `5. 서비스별 구독 매트릭스`
- The owner group's sections of `api-spec.md`, `erd.md`, `error-codes.md`, `event-contract.md`, and `policy-and-state.md`
- Related decision records

## Step A — Cycle table review (run before every issue)

1. Check if `docs/records/progress/{owner}.md` exists and the cycle table is filled.
    - If the file does not exist or the table is empty: skip to Step B. Use `planning.md` and the reference documents to plan instead.
    - After Step B, output the issue breakdown result as a draft table so the user can record it in the progress file.
2. Read the cycle table and verify the next issue matches the table order and dependency.
    - If a new issue was manually created, confirm its title and scope match the table.
    - If the table needs update (add / insert / change), propose changes and wait for Human gate.
3. Confirm dependency order before proceeding to Step B.

Human gate: user approves the cycle table state before issue planning begins.

## Steps — B. Category plan

1. List the owner group's features and priorities from `planning.md` > `3.2 요구사항 명세`.
2. Group them into categories. Each category should split into a few Issues.
3. For each category, list its prerequisites: earlier categories in the same domain, and other domains it depends on (the subscription matrix, and external dependencies in `api-spec.md`).
4. Keep the priority order, but put contracts that other domains wait for (events, internal APIs) first.
5. Give the grounds for each row as `document > section`.
6. Output the result and wait for approval.

## Steps — C. Issue breakdown

1. Split the approved category by `docs/guides/git-github.md` > `1.3 Issue 관리`. Split again any Issue over the size guideline.
2. For each Issue, give a title (`git-github.md` > `1.2 커밋·Issue·PR 제목`), a one-line scope, endpoints, the expected number of changed files, and dependencies.
3. For each Issue, separate what the documents settle from what needs a decision:
    - Are the API path, request, response, and status codes all in the specification?
    - Is the state transition in `policy-and-state.md` > `10. 주요 상태 전이`?
    - Are the needed error codes in `error-codes.md`?
    - Is the event schema in `event-contract.md` > `4. 이벤트 상세`?
    - Does it touch an item under `미결정 사항` in any document?
4. If an Issue needs a contract change (API, event, ERD, error code, policy), create a separate document Issue and make it a prerequisite (`git-github.md` > `1.7 문서 기준과 결정 기록`).
5. Cite grounds as `document > section > phrase`.
6. Output the result and wait for approval.

## Output format

Step B:

| 사이클 | 카테고리 | 포함 기능 | 우선순위 | 선행 조건 | 다른 도메인 영향 | 근거 |
| --- | --- | --- | --- | --- | --- | --- |

### 일정 위험
-

### 결정 필요
-

Step C:

### 문서 선행 이슈
| # | 제목 | 바꿀 문서 > 섹션 | 이유 | 영향 받는 담당 |
| --- | --- | --- | --- | --- |

### 구현 이슈
| # | 제목 | 범위 | 엔드포인트 | 예상 파일 수 | 의존 |
| --- | --- | --- | --- | --- | --- |

### 확정 사항
| 이슈 | 항목 | 내용 | 근거 |
| --- | --- | --- | --- |

### 결정 필요 사항
| 이슈 | 종류 | 질문 | 선택지 | 관련 문서 |
| --- | --- | --- | --- | --- |

## Do not

- Add features that are not in the planning document.
- Guess the milestone due date.
- Decide an item that needs a decision and list it as settled.
- Put endpoints or error codes that are not in the documents into an Issue's scope.

## Human gate

- Step A: user approves the cycle table state before category planning begins.
- Step B: approve the categories, adjust priority, and agree on dependency order with the other owner groups.
- Step C: settle the Issue scope; decide API signatures, state transitions, and error codes; register and merge the document Issues first.

## Tool differences

None
