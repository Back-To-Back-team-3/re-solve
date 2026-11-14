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
| `src/services/` | API types (`types.ts`), API functions (`api.ts`), client hooks (`hooks.ts`), mock API (`mock-api.ts`) |
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

- Do not use `any`. If a type is unknown, accept `unknown` and narrow it with a type guard.
- To add a package, explain why and get approval, then commit `package-lock.json` together.

## 9. File naming

- File names: kebab-case. Examples: `study-card.tsx`, `api-client.ts`.
- Exported component names: PascalCase. Non-component exports: camelCase.
- Next.js special files follow their own names: `page.tsx`, `layout.tsx`, `loading.tsx`, `error.tsx`, `not-found.tsx`.

## 10. Data fetching

- Server Components fetch data directly with `fetch`. Add `"use client"` only for client interaction, SSE, or React hooks.
- Define typed fetch functions in `src/services/api.ts`. Unwrap the `data` field of `ApiResponse<T>`; throw on `success: false` or non-2xx status.
- Client-side dynamic data (SSE, polling, user-triggered refresh) uses a client hook in `src/services/hooks.ts`.

## 11. Mutations

- Call `fetch` directly in event handlers. Wrap in `try/catch`; on error, set local `error` state.
- After a successful mutation, call `router.refresh()` to revalidate Server Component data.
- Do not add a dedicated mutation hook.

## 12. Feature file structure

- One file per domain: `features/{domain}.tsx`. Put all sub-components in that file.
- When a file exceeds 300 lines, extract sub-components to the same directory: `features/{domain}-{detail}.tsx`.
- Do not create subdirectories under `features/`.

## 13. localStorage keys

- Format: `re-solve:{feature}:{identifier}`. Examples: `re-solve:editor:draft:${problemId}`, `re-solve:sse:last-event-id`.
- Wrap every read and write in `try/catch`; `localStorage` can throw in private windows.

## 14. Style priority

1. Tailwind utility classes.
2. If a new design token is needed, add it to `@theme inline` in `src/styles/tokens.css`, then use it via Tailwind.
3. Responsive: use Tailwind breakpoints (`sm:`, `md:`, `lg:`). Do not write media queries directly.
4. Do not: reference CSS variables directly in `className` (`var(--)` belongs in `tokens.css`), use `style={{}}`, or use CSS Modules.

## 15. page.tsx role

- 1–5 lines. No logic, no imports beyond the feature component.
- Server Component by default: no `"use client"`.
- Wrap the feature component in `<Suspense>` for the loading boundary. The `error.tsx` sibling handles errors.
- All logic lives in the `features/` component.

## 16. Test criteria

- Pure calculation logic (date formatting, state decision functions): Vitest unit tests in `tests/unit/`.
- Screen flows (login → submit → result): Playwright E2E tests in `tests/e2e/`.
- Minimum coverage target: the Playwright E2E regression scenarios listed in `docs/dev/planning/planning.md` §1.5.

## 17. AI prompt override

- Rules §1–§17 apply in every session regardless of conversation context.
- For screen-specific overrides, add an HTML comment at the top of the relevant file:
  `<!-- AI: {screen} 작업 시 {rule} -->`
- When rules conflict, the narrowest scope wins: file comment > this AGENTS.md > root AGENTS.md.
- Reference: `docs/guides/ai/` collaboration guide.
