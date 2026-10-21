# 60. Troubleshooting

## Purpose

Find the cause of a build or test failure, or of a review comment, and produce a fix proposal and a development log entry.

## When to use

- When a local or CI build or test fails
- When applying review comments

## Inputs

- The full error message, or the review comment
- The related code and reference documents

## Steps

1. Summarize the symptom in one line.
2. List the possible causes from most to least likely, with a way to check each (command, log, or file location).
3. Ask before running any check command. After approval, run the commands to narrow the cause.
4. When the cause is confirmed, propose a fix. If the fix affects service boundaries, event contracts, or transaction structure, mark it 팀 협의 필요 and stop.
5. Write the fix commit message with the Issue's type and domain, and state the cause in the subject.
6. Finish with an entry in the development log troubleshooting format.

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

## Do not

- Propose a fix that hides the symptom without confirming the cause (disabling a test, ignoring an exception, raising a timeout). If it cannot be avoided, give the reason and a follow-up Issue.
- Proceed with a fix that needs team agreement without approval.

## Human gate

- The user approves running check commands and decides the fix.
- The fix is made through stage 30 Step B.

## Tool differences

None
