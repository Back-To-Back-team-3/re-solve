# AI 협업 안내

- 팀원 전용 문서로, Claude Code·Codex·Gemini CLI로 작업할 때 필요한 파일 구조, 도구별 사용법, GitHub MCP 연결 방법에 관한 내용이다.
- AI가 따르는 규칙은 저장소 루트 `AGENTS.md`에 있다.

## 1. 파일 구조

| 파일 | 읽는 도구 | 역할 |
| --- | --- | --- |
| `AGENTS.md` | 모든 도구 | AI 작업 규칙 원본 |
| `CLAUDE.md` | Claude Code | `AGENTS.md` import |
| `frontend/AGENTS.md` | 모든 도구 | 프론트엔드 추가 규칙 |
| `frontend/CLAUDE.md` | Claude Code | `frontend/AGENTS.md` import |
| `.mcp.json` | Claude Code | GitHub MCP 서버 |
| `.gemini/settings.json` | Gemini CLI | 컨텍스트 파일을 `AGENTS.md`로 지정 |

- AI 규칙을 바꿀 때는 `AGENTS.md`만 수정한다. 문서 운영 원칙은 Git/GitHub 메뉴얼 > 1.7 문서 기준과 결정 기록에 있다.
- `AGENTS.md`는 토큰 사용량을 줄이기 위해 영어로 작성한다. 기준 문서의 섹션 제목은 한국어 원문 그대로 가리킨다.
- AI는 답변과 Issue·PR·커밋을 한국어로 작성하고, 주석은 컨벤션·빌드 매뉴얼 > 5. 주석 작성을 따른다.

## 2. 도구별 사용 범위

| 기능 | Claude Code | Codex | Gemini CLI |
| --- | --- | --- | --- |
| `AGENTS.md` 로드 | `CLAUDE.md`의 import | 기본 지원 | `.gemini/settings.json` |
| 코드 구현·리뷰 보조 | 가능 | 가능 | 가능 |
| GitHub MCP | `.mcp.json` (저장소 공유) | `~/.codex/config.toml` (개인) | `~/.gemini/settings.json` (개인) |
| Issue·PR 등록 | MCP로 등록 (사용자 승인 후) | MCP로 등록 또는 본문 출력 후 직접 등록 | MCP로 등록 또는 본문 출력 후 직접 등록 |
| 로드 확인 | `/memory`, `/mcp` | "AGENTS.md §6을 요약해" | `/memory show` |

- Codex는 저장소 루트부터 실행한 디렉터리까지의 `AGENTS.md`를 읽는다. 루트에서 실행해도 `frontend/` 작업 전에는 루트 `AGENTS.md`의 안내에 따라 `frontend/AGENTS.md`를 읽는다.

## 3. 세션 시작

저장소 루트에서 AI를 실행하고, 첫 메시지로 담당 파트(서비스)·Issue 번호를 알린다. 담당은 기획서 > 2.1 도메인의 A~E를 기준으로 한다.

**도구별 실행 명령 (IntelliJ 내장 터미널, 저장소 루트에서)**
   
| 도구 | 실행 명령 |
| --- | --- |
| Claude Code | `claude` |
| Codex CLI | `codex` |
| Gemini CLI | `gemini` |

**AGENTS.md 로드 확인**

실행 후 아래 방법으로 AGENTS.md가 정상적으로 로드됐는지 확인한다.
   
| 도구 | 확인 방법 | 기대 결과 |
| --- | --- | --- |
| Claude Code | `/memory` 입력 | AGENTS.md 내용이 표시됨 |
| Claude Code | `/mcp` 입력 | `github` connected 표시 |
| Codex CLI | "AGENTS.md §6을 요약해" 입력 | 언어·주석 규칙 요약, 한국어 답변 |
| Gemini CLI | `/memory show` 입력 | AGENTS.md 내용이 표시됨 |

## 4. GitHub MCP 연결

GitHub Issue·PR을 조회하고, 사용자 승인 후 등록할 수 있도록 GitHub MCP 서버를 연결한다.

- **Claude Code** — 저장소의 `.mcp.json`을 공유하므로 추가 설정 없이 PAT만 등록하면 된다.
- **Codex CLI** — 개인 설정 파일(`~/.codex/config.toml`)에 등록한다. 저장소에 커밋되지 않으므로 각자 설정한다.
- **Gemini CLI** — 개인 설정 파일(`~/.gemini/settings.json`)의 `mcpServers` 블록에 등록한다.

### 4.1 준비물

- Docker Desktop 설치 및 실행 (`docker version`으로 확인)
- 사용하는 AI 도구 (Claude Code, Codex CLI, Gemini CLI 중 하나)
- `re-solve` 저장소 전용 Fine-grained PAT

### 4.2 PAT 발급

GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token

| 항목 | 값 |
| --- | --- |
| Resource owner | `Back-To-Back-team-3` |
| Repository access | Only select repositories → `re-solve` |
| Expiration | 프로젝트 종료일 이전 |
| Contents | Read-only |
| Issues | Read and write |
| Pull requests | Read and write |
| Metadata | Read-only (자동) |

- `Contents`를 Read and write로 올리지 않는다. 올리면 AI가 GitHub에서 파일을 직접 수정하거나 push할 수 있다.
- Resource owner는 본인 계정이 아니라 `Back-To-Back-team-3` 조직으로 설정한다. `re-solve` 저장소가 조직 소유이기 때문이다.
- 조직이 Fine-grained PAT 승인을 요구하면 발급 후 조직 관리자 승인을 받는다.

### 4.3 환경 변수 등록 (Windows)

토큰은 파일이 아니라 사용자 환경 변수 `GITHUB_PAT`로 등록한다. `.env`는 Docker Compose용이라 AI 도구가 읽지 않는다.

**1단계 — 환경 변수 등록 (PowerShell)**

```powershell
setx GITHUB_PAT "발급한_토큰"
```

`setx`는 현재 터미널에 즉시 반영되지 않는다. 등록 후 **IntelliJ를 완전히 종료하고 다시 실행**해야 내장 터미널에 적용된다.

**2단계 — 등록 확인 (새 터미널에서)**

```powershell
# 토큰 값을 직접 출력하지 않고 길이만 확인
($env:GITHUB_PAT).Length
```

토큰 길이(보통 90자 이상)가 출력되면 정상이다. 0이 나오면 IntelliJ 재시작 여부를 확인한다.

**Codex CLI 사용자 추가 단계**

Codex는 Docker 컨테이너에 `GITHUB_PERSONAL_ACCESS_TOKEN` 이름으로 값을 전달해야 한다. 셸 세션마다 아래 명령을 실행한다.

```powershell
$env:GITHUB_PERSONAL_ACCESS_TOKEN = $env:GITHUB_PAT
```

매번 실행하기 번거로우면 PowerShell 프로필(`$PROFILE`)에 추가한다.

```powershell
Add-Content $PROFILE "`n`$env:GITHUB_PERSONAL_ACCESS_TOKEN = `$env:GITHUB_PAT"
```

### 4.4 도구별 추가 설정

#### Claude Code

추가 설정 없음. `.mcp.json`이 저장소에 포함되어 있다.

첫 실행 시 아래 순서로 화면이 뜬다.

1. **신뢰 폴더 확인** — "Yes, I trust this folder" 선택
2. **MCP 서버 허용** — "Use this and all future MCP servers in this project" 선택
3. **fullscreen 렌더러** — 선택 무관 (UI 설정)
4. **로그인** — `/login` 입력 후 "Claude account with subscription" 선택

로그인 없이는 Issue 조회 등 MCP 기능이 동작하지 않는다.

#### Codex CLI

`~/.codex/config.toml`에 직접 추가한다. 파일이 없으면 새로 만든다.

```toml
[mcp_servers.github]
command = "docker"
args = ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "-e", "GITHUB_TOOLSETS", "ghcr.io/github/github-mcp-server"]
env_vars = ["GITHUB_PERSONAL_ACCESS_TOKEN"]

[mcp_servers.github.env]
GITHUB_TOOLSETS = "context,repos,issues,pull_requests"
```

- `env_vars`는 셸의 `GITHUB_PERSONAL_ACCESS_TOKEN` 값을 Docker 컨테이너에 그대로 전달한다. 4.3의 추가 단계에서 이 변수를 먼저 설정해야 한다.
- `[mcp_servers]`가 Codex CLI 공식 문서 기준이다. `[mcp.servers]`로 쓰면 서버가 등록되지 않는다.

#### Gemini CLI

`~/.gemini/settings.json`의 `mcpServers` 블록에 추가한다.

```json
{
  "context": { "fileName": ["AGENTS.md"] },
  "mcpServers": {
    "github": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "-e", "GITHUB_TOOLSETS", "ghcr.io/github/github-mcp-server"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_PAT}",
        "GITHUB_TOOLSETS": "context,repos,issues,pull_requests"
      }
    }
  }
}
```

### 4.5 연결 확인

MCP 서버 이미지를 먼저 받는다. 이 단계를 건너뛰면 연결이 실패한다.

```bash
docker pull ghcr.io/github/github-mcp-server
```

도구를 실행하고 아래 명령으로 연결을 확인한다.

| 도구 | 실행 | 확인 명령 | 기대 결과 |
| --- | --- | --- | --- |
| Claude Code | `claude` | `/mcp` | `github` connected 표시 |
| Codex CLI | `codex` | `/mcp` | `github` 표시 |
| Gemini CLI | `gemini` | `/mcp list` | `github` 표시 |

연결 후 열려 있는 Issue 하나의 제목을 물어 조회되는지 확인한다. 예: "Issue #19 제목을 알려줘"

### 4.6 권한 범위

| 단계 | 제한 |
| --- | --- |
| MCP 툴셋 | `context,repos,issues,pull_requests`만 연결한다. |
| PAT | `Contents: Read-only`라 파일 수정·push가 거부된다. Issue·PR·댓글은 작성할 수 있다. |
| 저장소 | Git/GitHub 메뉴얼 > 1.5 병합 정책에 따라 병합은 Mergify만 한다. |

- AI는 `AGENTS.md` §0에 따라 사용자 승인 전에는 Issue·PR·댓글을 등록하지 않는다.

### 4.7 문제 해결

| 증상 | 확인 |
| --- | --- |
| `github` failed | Docker Desktop 실행 여부, `docker pull` 성공 여부 |
| 시작 시 환경 변수 누락 경고 | `GITHUB_PAT` 등록 여부, IntelliJ 완전 종료 후 재시작 여부 |
| `($env:GITHUB_PAT).Length`가 0 | IntelliJ 재시작 여부 재확인, `setx` 명령을 PowerShell 관리자 권한으로 재실행 |
| Git Bash에서 환경 변수 안 읽힘 | Git Bash는 `setx` 변수를 즉시 못 읽는 경우가 있다. `export GITHUB_PAT=$(cmd //c echo %GITHUB_PAT%)`로 임시 적용 |
| Docker 명령을 찾을 수 없음 | Docker Desktop 실행 여부, PATH에 Docker 등록 여부 확인 |
| 401 Bad credentials (Claude Code) | 터미널에서 `($env:GITHUB_PAT).Length` 확인, 토큰 만료 여부 |
| 401 Bad credentials (Codex) | 셸에서 `$env:GITHUB_PERSONAL_ACCESS_TOKEN` 설정 여부 확인, 4.3 Codex 추가 단계 재실행 |
| Codex에서 `github` 서버가 등록되지 않음 | `config.toml` 키가 `[mcp_servers]`인지 확인. `[mcp.servers]`는 동작하지 않는다 |

### 4.8 보안

- 토큰을 `.mcp.json`·`.env`·Issue·PR·스크린샷에 남기지 않는다.
- 노출이 의심되면 즉시 GitHub에서 토큰을 폐기하고 재발급한다. push된 경우에도 커밋 정리보다 폐기를 먼저 한다.

## 5. 단계별 프롬프트

AI에게 단계 이름으로 요청하면 `AGENTS.md` §7에 따라 해당 프롬프트 파일을 읽고 진행한다. 프롬프트 파일은 AI가 읽는 문서라 영어로 작성했고, AI의 답변과 출력 표는 한국어다.

### 파이프라인 흐름

[00 세션 시작] ─ 모든 단계 앞에서 1회
│
[10 계획·이슈 쪼개기] A. 카테고리 ──(Human gate)── B. 이슈 ──(Human gate)
│                                           └ 계약 변경 필요 → 문서 Issue 먼저
[20 이슈 작성] ──(Human gate: 등록 승인)
│
[30 코드 구현] A. 계획 ──(Human gate)── B. 커밋마다 ──(Human gate: 커밋 승인)
│
[40 자체 리뷰] ── review-checklist.md ── (미통과 → 30 B)
[50 PR 작성] ──(Human gate: 등록 승인, Draft)
[40 리뷰 보조] (리뷰어) ── review-checklist.md ── 코멘트는 사람이 등록
[60 트러블슈팅] ── 실패·리뷰 반영 시 → 30 B

### 프롬프트 구조

모든 프롬프트 파일은 같은 8개 섹션으로 구성된다.

| 섹션 | 내용 |
| --- | --- |
| Purpose | 이 단계가 만드는 것 |
| When to use | 언제 이 단계를 시작하는가 |
| Inputs | AI가 읽어야 할 파일·값 |
| Steps | 순서대로 할 일 |
| Output format | 출력 표·본문 형식 (한국어 고정) |
| Do not | 이 단계에서 하면 안 되는 것 |
| Human gate | 사람이 결정할 것, AI가 멈출 지점 |
| Tool differences | Claude Code / Codex / Gemini CLI 차이 |

### 사용 방법

| 단계 | 프롬프트 | 요청에 포함할 내용 |
| --- | --- | --- |
| 세션 시작 | 00-session-start | 담당 [A~E], 이슈 #[번호] |
| 계획·이슈 쪼개기 | 10-plan | [마일스톤] 카테고리 계획 / [카테고리] 이슈 쪼개기 |
| 이슈 작성 | 20-issue-write | 이슈 제목 또는 10단계 확정 항목 번호 |
| 코드 구현 | 30-implement | 이슈 #[번호] 구현 계획 / 커밋 [n] 구현 |
| 리뷰 | 40-review | 자체 리뷰 / PR #[번호], 리뷰할 파일·범위 |
| PR 작성 | 50-pr-write | 이슈 #[번호] |
| 트러블슈팅 | 60-troubleshoot | 오류 메시지 전문 또는 리뷰 코멘트 |

### 주의사항

- 각 단계는 출력 후 사람의 승인을 기다린다. AI가 다음 단계로 스스로 넘어가지 않는다.
- 리뷰 기준은 `docs/guides/ai/review-checklist.md` 하나만 쓴다. 사람이 리뷰할 때도 같은 기준을 쓴다.
- 프롬프트가 실제 작업과 맞지 않으면 개인 설정에서 고쳐 쓰지 말고 개선 Issue를 만든다.

## 6. 참고 자료

- AGENTS.md 형식: <https://agents.md>
- Claude Code 메모리(`CLAUDE.md`, `@import`): <https://docs.claude.com/en/docs/claude-code/memory>
- Claude Code MCP(`.mcp.json`): <https://docs.claude.com/en/docs/claude-code/mcp>
- GitHub MCP Server: <https://github.com/github/github-mcp-server>
- Gemini CLI: <https://github.com/google-gemini/gemini-cli>
