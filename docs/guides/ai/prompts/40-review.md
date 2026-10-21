# 40. Review

## Purpose

Check changes against `docs/guides/ai/review-checklist.md`. Mode A is the author's self-review before a PR. Mode B helps a reviewer with a teammate's PR. People make the decisions in both modes.

## When to use

- Mode A: after all commits of stage 30, before creating the PR
- Mode B: when reviewing a teammate's PR

## Inputs

- Mode A: `git diff develop...HEAD`
- Mode B: the PR number, the PR body (especially `💬 리뷰어에게`), and the file and line range chosen by the reviewer
- Mode B: `docs/guides/git-github.md` > `4.1 리뷰어가 확인할 내용`
- `docs/guides/ai/review-checklist.md` and the documents each item points to

## Steps — A. Self-review

1. Look at `git diff --stat <base>...HEAD` — use `develop` for a normal PR and the preceding branch for a Stack PR — and choose the checklist sections that apply.
2. Open the grounds document for each item and judge it as 통과, 미통과, 해당 없음, or 미확인. For 통과, give the file and line, or the command run and its result.
3. Run the commands in section J. If you could not run a command, mark the item 미확인.
4. For 미통과 items, propose a fix only. Do not change anything.
5. Output the result and wait for approval.

## Steps — B. Review assist

1. Read the `💬 리뷰어에게` section of the PR body first. Check each request in it before anything else, and record the result per request.
2. Choose the checklist sections that apply to the code. Always include section M.
3. Analyze from three viewpoints: function (gaps or mismatches against the specification), code (responsibility, transactions, exceptions, concurrency), and tests (scenarios).
4. List separately anything in the PR body or comments that differs from the code.
5. Write each finding in the form required by `git-github.md` > `4.1 리뷰어가 확인할 내용`:
    - 지적: file path and line range, and what is wrong.
    - 근거: the checklist number and `document > section > phrase`, or the related code. For related code, give a permalink with the PR head commit SHA. If you do not know the SHA, give the path and lines and mark the link for the reviewer to add.
    - 대안: the code or method to change. Use a `suggestion` block for a change of a few lines. If there is more than one valid option, list the options.
6. Give each finding a severity (필수, 권장, 질문) and the submission type from `4.1 리뷰어가 확인할 내용`.
7. If there is no 필수 finding, draft an approval comment following `4.1 리뷰어가 확인할 내용`: the PR's subject in one line, the result for each request in `💬 리뷰어에게`, and if prior change requests were made, confirmation that each was addressed.
8. Output the analysis only. Do not post anything on GitHub.

## Output format

Mode A:

```markdown
### 적용 섹션
(적용 / 해당 없음과 이유)

### 판정
| # | 판정 | 근거 |
| --- | --- | --- |

### 실행한 명령
| 명령 | 결과 |
| --- | --- |

### 수정 필요
| # | 위치 | 수정 방안 |
| --- | --- | --- |
```

Mode B:

```markdown
### 리뷰어에게 확인 결과
| 확인 요청 | 결과 | 근거 |
| --- | --- | --- |

### 지적
| 위치 | 심각도 | 제출 | 지적 | 근거 | 대안 |
| --- | --- | --- | --- | --- | --- |

### PR 본문·주석과 코드의 차이
-

### 승인 코멘트 초안 (필수 지적이 없을 때)
- 주제:
- 리뷰어에게 확인 결과:
- 이전 변경 요청 반영 확인: (변경 요청이 있었던 경우)
```

## Do not

- Mark a command you did not run as 통과.
- Mode A: fix 미통과 items without approval.
- Mode B: post comments, approvals, or change requests on GitHub.
- Mode B: mark a matter of taste without grounds as 필수.
- Mode B: write a finding without all of 지적, 근거, and 대안.

## Human gate

- Mode A: the author decides how to fix each item and fixes it through stage 30 Step B. The author does not create the PR until there are no 미통과 items.
- Mode B: the reviewer decides which comments to post, and writes and posts them directly, including approvals and change requests, following `git-github.md` > `4.1 리뷰어가 확인할 내용`.

## Tool differences

| Claude Code | Codex, Gemini CLI |
| --- | --- |
| Mode B reads the PR diff through MCP (read only) | The reviewer pastes the code |
