# frontend/AGENTS.md

Also follow the root `AGENTS.md`. If a rule below conflicts with the root file, follow this file.

## 1. Tech stack

The source of versions is `frontend/package.json`. Only major versions that change usage are listed below.

| Area | Library | Major |
| --- | --- | --- |
| Framework | Next.js (App Router) | 16 |
| UI | React | 19 |
| Language | TypeScript | 5 |
| Styles | Tailwind CSS | 4 |
| Client state | Zustand | 5 |
| UI primitives | `radix-ui` | 1 |
| Code editor | `@monaco-editor/react` | 4 |
| Icons | `lucide-react` | 0 |
| Markdown rendering | `react-markdown` | 10 |
| Markdown plugin | `remark-gfm` | 4 |
| Unit tests | Vitest | 5 |
| E2E tests | Playwright | 1 |

- Use npm only. Follow `package.json` > `engines` for the Node version.

## 2. Commands (run in `frontend/`)

The source of commands is `package.json` > `scripts`.

```bash
npm ci
npm run dev
npm run lint
npm run typecheck
npm test
npm run test:install   # install Playwright browsers (first time only)
npm run test:e2e
```

- Run `npm run lint` and `npm run typecheck` before each commit.
- `test:install` and `test:e2e` set an environment variable with POSIX syntax. On Windows, run them in Git Bash or WSL.

## 3. Directory structure

Put new files in the existing structure below. If a new top-level directory is needed, ask the user first.

| Path | Purpose |
| --- | --- |
| `src/app/` | App Router routes (page, layout, loading, error) |
| `src/features/` | Feature components per screen |
| `src/components/` | Shared UI components |
| `src/services/` | API types (`types.ts`), mock API (`mock-api.ts`), data hooks (`hooks.ts`) |
| `src/mocks/` | Mock data (`fixtures.ts`) |
| `src/stores/` | Client state (Zustand) |
| `src/styles/` | `globals.css`, `tokens.css`, `fonts.css` |
| `tests/unit/` | Vitest tests |
| `tests/e2e/` | Playwright tests |

- Use the tsconfig alias `@/*` (`./src/*`) for new imports.

## 4. Routing and components

- Use the App Router only. Do not create a `pages/` directory.
- Server Components are the default. Add `"use client"` at the top only to files that need browser APIs, event handlers, or React hooks.

## 5. Styles

- Use Tailwind 4. Do not create a `tailwind.config.*` file. Add design tokens to `@theme inline` in `src/styles/tokens.css`.
- Do not add new inline styles (`style={{}}`) or CSS Modules.

## 6. State and data

- Manage client state with Zustand. Follow the existing `src/stores/workspace.tsx`: `createStore` with a React Context.
- Define new or changed API request and response types in `src/services/types.ts` from `docs/dev/specifications/api-spec.md`. Do not add fields that are not in the specification.
- Existing mock types were written before the specification. If they differ from it, report the difference and do not rewrite them outside the Issue scope.
- For screens without a real API, add a mock in the specification's format to `src/services/mock-api.ts` and `src/mocks/fixtures.ts` first. The owner of the screen's domain verifies the real API integration (`docs/dev/planning/planning.md` > `2.1 도메인`).
- Do not install a server-state library (TanStack Query, etc.) without user approval.

## 7. Code editor

- Use only the components of `@monaco-editor/react`.
- `postinstall` (`scripts/copy-monaco.mjs`) copies editor files to `public/monaco/`. If the editor is empty after install, check first whether this script ran.

## 8. Types and packages

- Do not use `any`. If a type is unknown,
