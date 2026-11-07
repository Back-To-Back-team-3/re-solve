# 60. Troubleshooting

## Purpose

Find the cause of a build or test failure, or of a review comment or a CodeRabbit review comment, and produce a fix proposal and a development log entry.

## When to use

- When a local or CI build or test fails
- When applying review comments
- When processing CodeRabbit review comments on a PR

## Inputs

- The full error message, or the review comment
- The related code and reference documents

## Steps — A. Build/test failure and review comments

1. Summarize the symptom in one line.
2. List the possible causes from most to least likely, with a way to check each (command, log, or file location).
3. Ask before running any check command. After approval, run the commands to narrow the cause.
4. When the cause is confirmed, propose a fix. If the fix affects service boundaries, event contracts, or transaction structure, mark it 팀 협의 필요 and stop.
5. Write the fix commit message with the Issue's type and domain, and state the cause in the subject.
6. (Optional) Finish with an entry in the development log troubleshooting format.

## Output format

```markdown
### 원인 후보
| 순위 | 후보 | 확인 방법 | 결과 |
| --- | --- | --- | --- |

### 수정 방안
- 대상:
- 커밋 메시지:
- 팀 협의 필요: 예 / 아니오

### 개발 일지
- **증상**:
- **원인**:
- **해결**:
- **교훈**:
```

## Steps — B. CodeRabbit review processing

When to use: when presented with CodeRabbit review comments on a PR.

1. Read all CodeRabbit comments.
2. For each comment, judge 해결 (address) or 철회 (decline — state why).
3. Report all judgments in one pass. Wait for approval.
4. After approval:
    - 해결 items: make all fixes, show diff + commit message (`fix(<domain>): 코드래빗 피드백 반영`). Wait for commit approval.
    - 철회 items: generate a reply draft per declined comment. Wait for approval before posting.
5. Post approved replies only.

## Do not

- Propose a fix that hides the symptom without confirming the cause (disabling a test, ignoring an exception, raising a timeout). If it cannot be avoided, give the reason and a follow-up Issue.
- Proceed with a fix that needs team agreement without approval.
- Post a CodeRabbit reply without approval.

## Human gate

- The user approves running check commands and decides the fix.
- The fix is made through stage 30 Step B.

## Tool differences

None
