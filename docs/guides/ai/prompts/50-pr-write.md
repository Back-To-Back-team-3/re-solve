# 50. PR writing

## Purpose

Write a PR body from the actual changes and verification results only, and register it after the user approves.

## When to use

- After stage 40 Mode A shows no 미통과 items and the user has pushed the branch

## Inputs

- `git diff <base>...HEAD`, `git log --oneline <base>..HEAD` — use `develop` for a normal PR and the preceding branch for a Stack PR
- `.github/pull_request_template.md`
- `docs/guides/git-github.md` > `1.4 PR 작성`, `1.6 AI로 Issue·PR을 작성할 때`
- The stage 40 Mode A result

## Steps

1. Follow `git-github.md` > `1.4 PR 작성`, `1.6 AI로 Issue·PR을 작성할 때`, and the PR template.
2. List only changes visible in the current diff.
3. In the test section, check only what appears under 실행한 명령 in stage 40, and give the commands and results in the details.
4. In the checklist, check only the items that passed in stage 40.
5. If files owned by another owner group changed, write the target, the reason, and a confirmation request line in the reviewer section. If you do not know the owner's GitHub account, leave it empty and ask the user.
6. If a decision record is related, name the file in the reviewer section.
7. Output the body and wait for approval. Register it on GitHub only after the user says to register it.

## Output format

- PR 제목
- PR 본문 (템플릿 형식, 안내 주석 제거)
- 본문 밖: base 브랜치, Draft 여부, 라벨, 비워 둔 값과 질문

## Do not

- Describe planned work as done.
- Check tests that are not in the stage 40 result.
- Register without approval.

## Human gate

- The user reviews the body, fills the empty values, and says to register it. The PR is created as a draft.
- The author assigns reviewers, checks CI, and switches the PR to Ready for review.

## Tool differences

| Claude Code | Codex, Gemini CLI |
| --- | --- |
| Creates a draft PR through MCP after approval and reports the number | Outputs the body; the user creates the draft PR |
