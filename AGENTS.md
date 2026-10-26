# AGENTS.md — Re:Solve

Working rules for AI agents in this repository. To change a rule, edit only this file. Do not add rules to tool-specific files such as `CLAUDE.md`.

This file does not copy rules from the reference documents; it only points to them. If this file and a reference document read differently, follow the reference document and report the difference to the user.

An `AGENTS.md` in a subdirectory also applies to work in that directory and wins on conflict. Before reading or editing files under `frontend/`, read `frontend/AGENTS.md`.

The reference documents are written in Korean. Locations below keep the original Korean section titles so you can search for them as written.

## 0. Absolute rules

1. Follow `docs/guides/git-github.md` > `0. 필수 사항` > `절대 원칙`.
2. The repository documents are the source of truth. Use conversations, Notion, or earlier sessions as grounds only when the content is reflected in a repository document or decision record.
3. Do not fill in values you could not confirm in a document (dates, Issue numbers, assignees, policy values, verification results). Leave them blank and ask the user.
4. Do not create or edit Issues, PRs, or comments on GitHub until the user approves. Do not run `git push`.

## 1. Before starting work

1. Confirm the user's name, owner group (A–E), and Issue number. If any is unknown, ask before working.
2. Check the services and areas that owner group owns in `docs/dev/planning/planning.md` > `2.1 도메인`. If the files to change are not owned by that group, stop and ask.
3. Follow `docs/guides/git-github.md` > `1.1 브랜치 전략` for branches. Only propose branch create or switch commands, and run them after the user approves.
4. Read the decision records in `docs/records/decisions/` related to the task.
5. Read the reference documents for the task from the table in §4. Do not write code based on a document you have not read.
6. If `docs/records/progress/{owner}.md` exists, read it to confirm the current cycle and recent completed work.
7. If `.personal/` directory exists, read files relevant to the current issue (development journal, personal reference docs) before reporting.

## 2. Document rules

- Follow `docs/guides/git-github.md` > `1.7 문서 기준과 결정 기록` for document operation.
- If a decision record conflicts with a reference document, follow the latest decision record and report the conflicting document, section, and phrase to the user.
- If the task needs a contract change (API, event, ERD, error code, policy), stop implementing and propose the document change first.
- Check whether something is implemented by reading code and configuration files, not PR descriptions.
- Give document locations as `document > section > phrase`, never as line numbers.

## 3. Scope and prohibitions

- Do not modify files outside the Issue scope. If a change outside the scope is needed, state the target and reason, and modify it only after the user approves.
- Files owned by another owner group are read-only. If a change is needed, state the target and reason, and modify it only after the user replies that the owner has confirmed it.
- Before each commit, show the changed file list and the commit message and get approval. Follow `docs/guides/git-github.md` > `1.2 커밋·Issue·PR 제목` for the subject.
- Write Issues and PRs by `docs/guides/git-github.md` > `1.6 AI로 Issue·PR을 작성할 때`.
- Before each commit, follow `docs/guides/convention-build-manual.md` > `4. 평소 개발 시 — 매 커밋 전 루틴`. Report only the commands you actually ran, with their results.
- Never write secrets (tokens, passwords, keys) to files, commits, Issues, or PRs. Leave example values empty.
- Do not reference `.personal/` directory paths or file names anywhere in the repository: commit messages, code comments, Issue bodies, PR bodies, or any other tracked file.
- After creating a branch, push it immediately and confirm the branch appears in the repository. Open a Draft PR before starting implementation.

## 4. Reference documents

The source of the document list is `docs/README.md`. The table below shows which document to read first for each kind of work.

| Work | Read first |
| --- | --- |
| Any work | `docs/records/decisions/` |
| Feature scope, priority | `docs/dev/planning/planning.md` |
| Domain logic, state transitions, validation responsibility | `docs/dev/planning/policy-and-state.md` |
| Calls between services | `docs/dev/architecture/system-architecture.md`, `docs/dev/architecture/diagrams.md` |
| Controllers, DTOs, error responses | `docs/dev/specifications/api-spec.md`, `docs/dev/specifications/error-codes.md` |
| Entities, Flyway migrations | `docs/dev/specifications/erd.md` |
| Publishing, consuming events | `docs/dev/specifications/event-contract.md` |
| Scheduled jobs | `docs/dev/specifications/scheduler.md` |
| Environment variables | `docs/operations/env-example.md` |
| Branches, commits, Issues, PRs | `docs/guides/git-github.md` |
| Writing code, before a commit | `docs/guides/convention-build-manual.md` |
| AI collaboration guides | `docs/guides/ai/README.md`, prompts in `docs/guides/ai/prompts/` |

## 5. Owners

- Owner groups and the services they own: `docs/dev/planning/planning.md` > `2.1 도메인`. The planning document lists groups, not member names, so take the group from the user's declaration.
- Owners of each API specification section: `docs/dev/specifications/api-spec.md` > `작성 분담`.
- The `domain` value for branches and commits: `docs/guides/git-github.md` > `1.1 브랜치 전략`.
- If a GitHub account is needed (for example, to request reviewers), ask the user. Do not guess.

## 6. Language and comments

- Reply to the user in Korean.
- Write Issue, PR, and commit subjects and bodies in Korean. Keep `<type>(<domain>)`, code identifiers, and file paths as they are.
- Write code comments by `docs/guides/convention-build-manual.md` > `5. 주석 작성`.

## 7. Stage prompts

When the user asks for one of the stages below, read the file first and follow its steps and output format. After each stage's output, wait for the user's approval. Do not move to the next stage on your own.

| Request | Prompt |
| --- | --- |
| Start a session, declare the owner group | `docs/guides/ai/prompts/00-session-start.md` |
| Plan a milestone, break down Issues | `docs/guides/ai/prompts/10-plan.md` |
| Write or register an Issue | `docs/guides/ai/prompts/20-issue-write.md` |
| Implement | `docs/guides/ai/prompts/30-implement.md` |
| Self-review, help review a PR | `docs/guides/ai/prompts/40-review.md` |
| Write or register a PR | `docs/guides/ai/prompts/50-pr-write.md` |
| Build or test failure, applying review comments | `docs/guides/ai/prompts/60-troubleshoot.md` |

Use only `docs/guides/ai/review-checklist.md` as the review standard.
