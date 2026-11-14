# Re:Solve Frontend

Re:Solve 프론트엔드 — Next.js 16 App Router 기반의 알고리즘 학습 플랫폼 클라이언트.

## 개발 환경 설정

Node.js 버전은 `package.json` > `engines` 필드를 따릅니다.

```bash
# 의존성 설치
npm ci

# 개발 서버 실행
npm run dev
```

백엔드 API를 연동할 때는 프로젝트 루트에 `.env.local` 파일을 생성합니다.

```
NEXT_PUBLIC_API_URL=http://localhost:8080
```

## 주요 명령어

| 명령어 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 실행 |
| `npm run lint` | ESLint 검사 |
| `npm run typecheck` | TypeScript 타입 검사 |
| `npm test` | Vitest 단위 테스트 |
| `npm run test:e2e` | Playwright E2E 테스트 |

> `test:install` 및 `test:e2e`는 환경변수를 POSIX 문법으로 설정합니다. Windows에서는 Git Bash 또는 WSL에서 실행하세요.

## 디렉터리 구조

| 경로 | 설명 |
|---|---|
| `src/app/` | App Router 라우트 (`page.tsx`, `layout.tsx`, `loading.tsx`, `error.tsx`) |
| `src/features/` | 도메인별 화면 컴포넌트 |
| `src/components/` | 공유 UI 컴포넌트 |
| `src/services/` | API 타입 (`types.ts`), API 함수 (`api.ts`), 클라이언트 훅 (`hooks.ts`) |
| `src/mocks/` | Mock 데이터 (`fixtures.ts`) |
| `src/stores/` | Zustand 클라이언트 상태 |
| `src/styles/` | `globals.css`, `tokens.css`, `fonts.css` |
| `tests/unit/` | Vitest 단위 테스트 |
| `tests/e2e/` | Playwright E2E 테스트 |

## 개발 규칙

파일 구조·스타일·테스트·데이터 조회 규칙은 [AGENTS.md](./AGENTS.md)를 따릅니다.
