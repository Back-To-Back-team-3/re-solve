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

- Docker Desktop 실행 (`docker version`으로 확인)
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

### 4.4 도구별 추가 설정

#### Claude Code

추가 설정 없음. `.mcp.json`이 저장소에 포함되어 있다.

#### Codex CLI

`~/.codex/config.toml`에 직접 추가한다. 파일이 없으면 새로 만든다.

```toml
[mcp.servers.github]
command = "docker"
args = ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "-e", "GITHUB_TOOLSETS", "ghcr.io/github/github-mcp-server"]

[mcp.servers.github.env]
GITHUB_PERSONAL_ACCESS_TOKEN = "${GITHUB_PAT}"
GITHUB_TOOLSETS = "context,repos,issues,pull_requests"
```

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

MCP 서버 이미지를 먼저 받는다.

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
| 401 Bad credentials | 터미널에서 `($env:GITHUB_PAT).Length` 확인, 토큰 만료 여부 |
| 403 Resource not accessible | PAT 저장소 범위·권한, 조직 승인 여부 |
| Issue 생성은 되고 파일 수정은 거부됨 | 정상 (`Contents: Read-only`) |

### 4.8 보안

- 토큰을 `.mcp.json`·`.env`·Issue·PR·스크린샷에 남기지 않는다.
- 노출이 의심되면 즉시 GitHub에서 토큰을 폐기하고 재발급한다. push된 경우에도 커밋 정리보다 폐기를 먼저 한다.

## 5. 참고 자료

- AGENTS.md 형식: <https://agents.md>
- Claude Code 메모리(`CLAUDE.md`, `@import`): <https://docs.claude.com/en/docs/claude-code/memory>
- Claude Code MCP(`.mcp.json`): <https://docs.claude.com/en/docs/claude-code/mcp>
- GitHub MCP Server: <https://github.com/github/github-mcp-server>
- Gemini CLI: <https://github.com/google-gemini/gemini-cli>
