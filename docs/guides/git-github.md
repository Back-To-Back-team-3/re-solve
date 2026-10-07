# Git/GitHub 메뉴얼

## 0. 필수 사항

### 절대 원칙

1. 일반 작업 브랜치는 최신 `develop`에서 만든다. 배포 버전의 긴급 수정인 `hotfix/*`만 `main`에서 만든다.
2. `main`·`develop`에는 직접 push하지 않는다. 변경은 PR로 반영한다.
3. 일반 PR은 팀원 2명, `main ↔ develop` PR은 팀원 3명의 승인이 필요하다.
    - CI 성공, 충돌 없음, 미해결 리뷰 대화 없음도 병합 조건이다.
4. 직접 실행하지 않은 테스트·빌드는 PR에서 완료로 표시하지 않는다.(현재 CI는 테스트를 실행하지 않는다.)

### 작업 사이클

| 순서 | 할 일 | 확인할 내용 |
| --- | --- | --- |
| 1 | Issue 생성 | 작업 목적과 범위를 정한다. |
| 2 | `develop` 최신화·브랜치 생성 | Issue 번호와 담당 도메인을 브랜치명에 넣는다. |
| 3 | 작업·커밋 | 변경 파일과 staging 내용을 확인한다. |
| 4 | 브랜치 push·Draft PR 생성 | base가 `develop`인지 확인한다. |
| 5 | CodeRabbit·CI 확인과 수정 | 실제로 수행한 검증만 PR에 적는다. |
| 6 | Ready for review·팀원 리뷰 | 승인, 변경 요청, 미해결 대화를 확인한다. |
| 7 | Mergify Queue 병합 | 병합 결과와 작업 브랜치 삭제를 확인한다. |
| 8 | `develop` 최신화 | 다음 Issue를 시작하기 전에 pull한다. |

---

## 1. Git 및 협업 규칙

### 1.1 브랜치 전략

일반 작업 브랜치는 최신 `develop`에서 분기해 PR로 `develop`에 반영한다. 배포 버전의 긴급 수정인 `hotfix/*`만 `main`에서 분기한다.

| 브랜치 | 용도 | 규칙 |
| --- | --- | --- |
| `main` | 배포 기준 | 직접 push 금지, `develop`의 릴리즈 PR과 `hotfix/*` PR을 받는다. |
| `develop` | 개발 통합 | 직접 push 금지, 일반 작업 PR을 받는다. |
| `feat/*` | 기능 추가 | 새로운 API, 도메인 기능, 화면 흐름 구현 |
| `fix/*` | 버그 수정 | 기존 기능의 오류·예외·정합성 문제 수정 |
| `docs/*` | 문서 수정 | README, API 명세, 기획 문서 수정 |
| `chore/*` | 설정·기타 작업 | 빌드·의존성·CI·환경 설정 변경 |
| `test/*` | 테스트 추가 | 단위·통합 테스트 작성 또는 수정 |
| `refactor/*` | 코드 개선 | 기능 변경 없이 구조 개선 |
| `style/*` | 코드 형식 수정 | 포맷·들여쓰기·공백·import 정리 |
| `hotfix/*` | 배포 버전 긴급 수정 | `main`에 병합한 뒤 `develop`에도 동일 변경 반영 |
- 브랜치명은 다음 형식을 사용한다.

```
<type>/<issue-number>-<domain>-<summary>

feat/12-contest-exam
fix/35-judge-submission
docs/53-contest-api-spec
```

- 브랜치명과 커밋 제목의 `domain`은 작업 대상에 맞춰 아래 값을 사용한다.

| 작업 대상 | `domain` |
| --- | --- |
| `member-service` | `member` |
| `analytics-service` | `analytics` |
| `problem-service` | `problem` |
| `judge-service` | `judge` |
| `contest-service` | `contest` |
| `study-service` | `study` |
| `ai-service` | `ai` |
| `notification-service` | `notification` |
| `integration-service` | `integration` |
| `api-gateway` | `api-gateway` |
| `frontend` | `frontend` |
| 공통 인프라·설정 | `infra` |
| 그 밖의 문서 | `docs` |

### 1.2 커밋·Issue·PR 제목

```
<type>(<domain>): <subject>

feat(contest): 모의 코딩테스트 생성 기능 추가
fix(judge): 중복 제출 방지
docs(contest): 시험 API 명세 반영
```

| `type` | 의미 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 수정 |
| `chore` | 설정·빌드·기타 작업 |
| `test` | 테스트 추가·수정 |
| `refactor` | 동작을 바꾸지 않는 코드 개선 |
| `style` | 코드 형식 수정 |
| `hotfix` | 배포 버전 긴급 수정 |
- 한 커밋에는 하나의 작업 목적을 담고, 제목은 실제 변경을 설명하도록 적는다.
- 같은 Issue의 브랜치·커밋·Issue·PR 제목에는 같은 `type`과 `domain`을 사용한다.

### 1.3 Issue 관리

- Issue는 Bug·Feature·Task 템플릿 중 작업 성격에 맞는 것을 선택한다.
- 목적, 작업 내용, 중요도, 예상 규모, 완료 기한, 담당자를 확인하고 해당 Milestone에 넣는다.
- 작업 범위를 추적할 수 있도록 기본적으로 하나의 Issue에 하나의 작업 브랜치를 사용한다. 범위가 커지면 Issue를 나눈다.
- 완료 조건이나 범위가 바뀌면 Issue에도 반영한다.

### 1.4 PR 작성

| PR 종류 | base | compare |
| --- | --- | --- |
| 일반 작업 | `develop` | 작업 브랜치 |
| 릴리즈 | `main` | `develop` |
| 긴급 수정 | `main` | `hotfix/*` |
| 긴급 수정 역반영 | `develop` | `main` |
| Stack 후속 PR | 바로 아래 작업 브랜치 | 후속 작업 브랜치 |
- PR은 Draft로 열고 `.github/pull_request_template.md`의 개요·구현 내용·테스트·관련 이슈·리뷰어 안내·체크리스트를 작성한다.
- 테스트 항목은 실제 수행한 것만 체크한다. 실행하지 않았다면 `테스트 미진행`과 이유를 적는다.
- 병합 시 Issue를 닫으려면 `Closes #번호`, 연결만 하려면 `Related to #번호`를 적는다.
- Stack의 2번째 이상 PR에만 `🧱 stack` 라벨을 붙인다. 선행 PR이 병합되면 후속 PR의 base와 diff를 다시 확인한다.

### 1.5 병합 정책

| PR | 필요 승인 | 병합 방식 |
| --- | --- | --- |
| 일반 작업·Stack·`hotfix/* → main` | 2명 이상 | Mergify Queue, squash |
| `develop → main`·`main → develop` | 3명 이상 | Mergify Queue, merge commit |
- Draft 해제, 충돌 없음, 변경 요청 없음, 미해결 리뷰 대화 없음, CI(`quality-check`) 성공이 필요하다. 수정 push 후에는 승인 상태를 다시 확인한다.
- `quality-check`는 `spotlessCheck`, `checkstyleMain`, `checkstyleTest`, `compileTestJava`를 실행한다. (현재 테스트 실행은 포함하지 않는다.)
- GitHub Ruleset은 사람의 직접 병합을 제한하고 Mergify에 예외를 부여한다. 병합 후 작업 브랜치 자동 삭제가 설정돼 있다.

### 1.6 AI로 Issue·PR을 작성할 때

- 이 메뉴얼과 저장소의 해당 템플릿을 읽고 작성한다. 이전 대화나 개인 AI 설정이 없어도 같은 기준을 사용한다.
- Issue는 작업 성격에 맞는 `.github/ISSUE_TEMPLATE/` 파일을 사용하고, PR은 `.github/pull_request_template.md`를 사용한다.
- Issue 작성 전에는 작업 목적·범위·작성자·완료 기한을 확인한다. 중요도·예상 규모는 확인한 범위를 기준으로 판단한다.
- PR 작성 전에는 관련 Issue·현재 diff·실제로 수행한 검증과 결과를 확인한다. 계획만 있는 내용을 구현 완료로 작성하지 않는다.
- Issue 작성자는 본인 이름에 체크한다. 다른 팀원의 체크는 해당 팀원이 확인한 경우에만 표시한다.
- 필요한 정보나 자료에 접근할 수 없으면 누락된 부분을 확인한다. 날짜·담당자·링크·검증 결과를 추측하지 않는다.
- 항목별 문장 형식은 템플릿을 따른다. 예시는 실제 작업의 사실로 복사하지 않는다.
- 게시 전 제목 형식, 작업 범위, 링크·Issue 번호, 체크 여부를 확인한다. 본문에는 안내 주석·예시·자리표시자를 남기지 않는다.
- 저장소의 템플릿 원본에는 안내 주석을 유지한다.

---

## 2. 처음 작업할 때

- GitHub 저장소의 `Code`에서 URL을 복사한다.

```bash
# 저장소 clone 후 현재 브랜치와 작업 파일 확인
git clone <저장소-URL>
cd re-solve
git branch --show-current
git status
```

- `Java 21`, 빌드·코드 형식은 "Spotless & Code 컨벤션 & Build 메뉴얼"을 따른다.
- 환경 변수와 Docker Compose는 ".env.example" 문서를 확인한다.

---

## 3. 일반 작업 흐름

### 3.1 `develop` 최신화와 브랜치 생성

Issue 번호와 도메인을 실제 작업에 맞게 바꾼다.

```bash
# 작업 전 상태 확인
git status

# 최신 develop에서 Issue용 브랜치 생성
git switch develop
git pull --ff-only origin develop
git switch -c feat/이슈번호-domain-summary
```

- 이미 만든 브랜치에서 작업을 이어 갈 때는 `git switch -c`로 새 브랜치를 다시 만들지 않는다.

### 3.2 변경 확인·커밋·push

```bash
# 변경한 파일과 내용을 확인한 뒤 staging
git status
git diff
git add .

# staging된 내용 확인 후 커밋
git diff --cached
git commit -m "type(domain): summary"

# 최초 push에만 -u 사용
git push -u origin feat/이슈번호-domain-summary
```

- `git add .` 전에 다른 Issue의 변경이 섞이지 않았는지 확인한다.
- 필요한 파일만 담으려면 `git add <파일>`을 사용한다.
- 이후 push는 `git push`로 한다.

### 3.3 Draft PR과 리뷰 요청

1. GitHub 저장소에서 `Compare & pull request` 또는 `Pull requests → New pull request`를 연다.
2. base `develop`, compare 본인 작업 브랜치를 확인하고 `Create draft pull request`로 만든다.
3. PR 템플릿에 완료한 변경과 실제 수행한 검증을 작성한다.
    - CodeRabbit 리뷰와 CI 결과를 확인하고 필요한 수정을 같은 브랜치에 push한다.
4. 수정이 끝나면 `Ready for review`로 바꾸고 팀원 리뷰를 요청한다.

---

## 4. 리뷰와 병합

### 4.1 리뷰어가 확인할 내용

- `Files changed`에서 Issue 범위와 실제 변경이 맞는지 확인한다.
- 동작, 예외 상황, 문서 반영, 수행한 검증 내용을 확인한다.
    - 모르는 부분은 PR 대화에서 질문한다.
- 문제가 해결되면 `Review changes → Approve`로 승인한다.
    - 수정이 필요하면 변경 요청을 남긴다.

### 4.2 병합 전 확인

1. Draft가 해제됐는지 확인한다.
2. 해당 PR의 승인 수, 변경 요청, 미해결 대화, 충돌 여부를 확인한다.
3. CI(`quality-check`) 성공을 확인한다.
4. 조건이 충족되면 Mergify Queue의 병합 결과를 확인한다.

### 4.3 병합 후

```bash
# 다음 Issue를 시작하기 전에 develop 최신화
git switch develop
git pull --ff-only origin develop
git status
```

- Stack 후속 PR이 있다면 선행 PR 병합 후 base와 diff를 확인한다.

---

## 5. 작업 중 `develop`이 바뀌었을 때

### 5.1 변경 사항을 커밋한 상태

```bash
# develop 최신화 후 작업 브랜치에 병합
git status
git switch develop
git pull --ff-only origin develop
git switch feat/이슈번호-domain-summary
git merge develop
```

### 5.2 아직 커밋하지 않은 변경 사항이 있을 때

- 작업 내용을 먼저 커밋하거나, 잠시 보관해야 한다면 stash를 사용한다.

```bash
# 현재 변경을 임시 보관하고 develop 반영
git status
git stash push -u -m "작업내용"
git switch develop
git pull --ff-only origin develop
git switch feat/이슈번호-domain-summary
git merge develop

# 보관한 변경 복원
git stash pop
```

- `git stash pop`에서 충돌이 나면 파일을 해결한 뒤 상태를 확인한다.
- stash가 남았는지도 `git stash list`로 확인한다.

### 5.3 충돌이 발생했을 때

1. `git status`로 충돌 파일을 확인하고, 양쪽 변경을 읽는다.
2. IntelliJ의 `Resolve Conflicts` 또는 편집기에서 의도에 맞게 합친다. `<<<<<<<`, `=======`, `>>>>>>>` 표시는 최종 파일에서 제거한다.
3. 해결한 파일만 staging하고 merge를 마무리한다.

```bash
# 해결한 파일 확인 후 merge 완료
git status
git add <충돌을-해결한-파일>
git commit
```

- 변경 의도가 불분명하면 해당 담당자와 확인한다.
- 공통 설정 파일과 API·DB 계약을 함께 바꿀 때는 관련 담당자에게 변경 내용을 공유한다.

---

## 6. 실수했을 때

### 잘못 staging한 파일

```bash
# staging에서만 제거하며 파일 수정 내용은 유지
git restore --staged <파일>
```

### push 전 마지막 커밋 메시지 수정

```bash
# 아직 push하지 않은 마지막 커밋에만 사용
git commit --amend -m "type(domain): summary"
```

- 이미 push한 커밋은 팀원에게 영향을 줄 수 있으므로 임의로 amend하거나 강제 push하지 않는다.
- 수정할 내용은 새 커밋으로 올린다.

### 파일 수정 내용을 버릴 때

```bash
# 해당 파일의 커밋 전 수정 내용을 삭제
git restore <파일>
```

- 이 명령으로 버린 수정 내용은 복구하기 어려우므로 파일과 diff를 먼저 확인한다.

---

end.
