# API 명세서 v1.1

> 최종 수정일: 2026.10.07(수)
> 

---

### 작성 분담

| 절 | 담당 | 비고 |
| --- | --- | --- |
| 0. 공통 규약 | 공통 | 0.4 에러 코드·0.6 권한 요약은 각자 자기 서비스 행 추가 |
| 1. 인증·회원·진단 (`member-service`) | 담당 E | 태그별 진단·레벨 포함 |
| 2. 문제 (`problem-service`) | 담당 B | 풀이 공유·리뷰 포함 |
| 3. 제출·채점 (`judge-service`) | 담당 B | 코드 실행(Run) 포함 |
| 4. 모의 코테·대회 (`contest-service`) | 담당 C | 시험·대회 관리자 API 포함 |
| 5. 스터디 (`study-service`) | 담당 D | 스터디 추천 포함 |
| 6. AI 기능 (`study-service` AI 모듈, `ai-service` 후보) | 담당 D | 힌트·가드레일 |
| 7. 알림 (`notification-service`) | 담당 A | SSE 포함 |
| 8. GitHub 연동 (`integration-service`) | 담당 A |  |
| 9. 관리자 API 색인 | 공통 | 각 서비스 절의 관리자 API 목록 |
| 부록 | 공통 | 각자 내부 API·이벤트 행 추가 |

---

## 0. 공통 규약

### 0.1 기본 규칙

| 항목 | 값 |
| --- | --- |
| 외부 Base URL | `/api/v1` (API Gateway 경유) |
| 내부 Base URL | `/internal/v1` (서비스 간 호출 전용, Gateway 라우팅 제외, 외부 비공개, 서비스 계정 인증) |
| 인증 방식 | `Authorization: Bearer {accessToken}` (JWT). Gateway가 검증 후 `X-User-Id`, `X-User-Role` 전달. 외부에서 보낸 같은 이름의 헤더는 Gateway가 제거 |
| 관리자 경로 | `/api/v1/admin/**`은 Gateway와 소유 서비스 양쪽에서 `ADMIN`을 검증한다. `ADMIN`이 아닌 사용자도 호출하는 운영 API는 `/admin` 아래에 두지 않는다 |
| 상관관계 ID | Gateway가 `X-Correlation-Id` 발급, HTTP·메시지 전 구간 전파 |
| Content-Type | `application/json; charset=UTF-8` |
| 경로 | 리소스는 영문 소문자 복수형, 여러 단어는 kebab-case. 공개 경로에 서비스 이름을 넣지 않는다. 리소스로 표현하기 어려운 명령은 `POST` + 하위 경로, 관계 해제는 `DELETE` |
| 날짜·시각 | Asia/Seoul 기준 ISO 8601 (`2026-09-21T10:15:30+09:00`). 이벤트·시험 접수 시각은 마이크로초 6자리. DB는 `DATETIME(6)` |
| 페이지네이션 | `?page=0&size=20&sort=field,desc` (`page` 0부터, `size` 기본 20·최대 100) |
| 목록 응답 | `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext` |
| 검색 | 검색어는 `keyword`, 도메인 필터는 의미가 드러나는 이름. 다중 값은 반복 파라미터 (`tagIds=1&tagIds=3`) |
| 멱등 | 같은 키·같은 본문은 최초 응답 반환, 같은 키·다른 본문은 `IDEMPOTENCY_KEY_CONFLICT`(422) |
| 버전 | 호환되지 않는 변경이 있을 때만 새 버전 추가 |

**공통 요청 헤더**

| 헤더 | 대상 | 설명 |
| --- | --- | --- |
| `Authorization` | 로그인 필요 API | `Bearer {accessToken}`. 공개 API는 선택 |
| `Idempotency-Key` | 멱등 지정 API | 1~100자 ASCII. 같은 본문 재요청에 재사용 |
| `X-Correlation-Id` | 전체 | 없으면 Gateway 발급 |
| `Authorization` (내부) | `/internal/v1/**` | `Bearer {serviceToken}`. 호출 서비스 allowlist 검증 |

### 0.2 식별자(ID) 규칙

- 경로 변수는 리소스 PK를 사용한다. 예: `/studies/{studyId}` → `studies.id`
- DB에서 `BIGINT`인 식별자는 API JSON에서 **문자열**로 표현한다. 예: `"studyId": "12"`
- 열거형 값(상태·판정·난이도)은 의미가 드러나는 문자열로 표현한다. 예: `"status": "APPROVED"`
- 난이도 `difficulty`는 1~3 정수로 표현한다.
- 태그 레벨은 `LV0` / `LV1` / `LV2` / `LV3` / `MASTER` 문자열로 표현한다.
- 언어 식별자는 `java` / `python3` / `cpp`를 쓴다.
- 요청자 식별자(회원 ID)는 외부 API의 요청 바디나 파라미터로 받지 않고 인증 정보(`X-User-Id`)로만 판단한다. 내부 API는 호출 서비스가 확인한 `memberId`를 전달한다.
- 문제 회차는 식별·고정에 `problemRevisionId`, 표시에 `revisionNumber`를 쓴다.
- 함수형 입력값은 정밀도 보존을 위해 JSON 텍스트(`argumentTexts`)로 주고받는다 (2.3.0).

### 0.3 공통 성공 / 에러 응답

```json
// 조회 성공
{
  "success": true,
  "message": null,
  "data": {
    "studyId": "12",
    "name": "코딩 테스트 스터디"
  }
}

// 작업 성공
{
  "success": true,
  "message": "스터디 가입이 완료되었습니다.",
  "data": {
    "studyId": "12"
  }
}

// 목록 조회 성공
{
  "success": true,
  "message": null,
  "data": {
    "content": [
      {
        "problemId": "301",
        "title": "두 수의 합"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  }
}

// 실패
{
  "success": false,
  "error": {
    "code": "STUDY_ALREADY_APPLIED",
    "message": "이미 신청했거나 참여 중인 스터디입니다.",
    "details": []
  }
}

// 검증 실패
{
  "success": false,
  "error": {
    "code": "COMMON_INVALID_REQUEST",
    "message": "입력값을 확인해 주세요.",
    "details": [
      {
        "field": "name",
        "reason": "스터디 이름은 필수입니다."
      }
    ]
  }
}

```

- `204 No Content`, SSE 스트림, 이미지 바이너리 응답은 공통 응답 형식을 적용하지 않는다.
- 내부 API는 공통 응답 형식 없이 데이터 객체를 그대로 반환한다. 오류는 공통 오류 형식을 쓴다.
- 프론트엔드는 `error.message`가 아니라 `error.code`로 분기한다.

### 0.4 에러 코드 표

[에러 코드 표](error-codes.md)

### 0.5 상태값 요약

| 대상 | 값 |
| --- | --- |
| 회원 `status` | `ACTIVE` / `SUSPENDED` / `WITHDRAWN` |
| 회원 `role` | `USER` / `ADMIN` |
| 태그 레벨 | `LV0` / `LV1` / `LV2` / `LV3` / `MASTER` |
| 진단 응시 `status` | `IN_PROGRESS` / `CLOSED` / `SCORED` |
| 문제 `scope` · `visibility` | `GENERAL` / `EXAM_ONLY` · `PRIVATE` / `PUBLIC` / `ARCHIVED` |
| 제출 `context` | `PRACTICE` / `EXAM` / `CONTEST` / `DIAGNOSIS` |
| 제출 `status` (judge) | `QUEUED` / `JUDGING` / `RETRY_WAITING` / `COMPLETED` / `FAILED` |
| 판정 `verdict` | `AC` / `WA` / `TLE` / `MLE` / `RE` / `CE` |
| 시험 `status` | `SCHEDULED` / `IN_PROGRESS` / `CLOSED` / `FINALIZED` / `CANCELED` |
| 시험·대회 참가자 `status` | `REGISTERED` / `STARTED` / `FINISHED` / `ABSENT` / `CANCELED` |
| 시험·대회 접수 `status` (contest) | `ACCEPTED` / `REQUESTED` / `JUDGED` / `FAILED` |
| 결과 공개 `resultStatus` | `HIDDEN` / `PROVISIONAL` / `FINALIZED` |
| 관리자 작업 `state` | `PENDING` / `SUCCEEDED` / `FAILED` |
| 대회 `status` | `SCHEDULED` / `RUNNING` / `ENDED` / `FINALIZED` / `CANCELED` |
| 스터디 `status` | `RECRUITING` / `ACTIVE` / `CLOSED` |
| 스터디 가입 `status` | `PENDING` / `APPROVED` / `REJECTED` / `CANCELED` / `EXPIRED` / `LEFT` / `REMOVED` |
| 스터디 역할 | `LEADER` / `MANAGER` / `MEMBER` |
| 문제집 `status` · 문제 `status` | `SCHEDULED` / `OPEN` / `CLOSED` · `ACTIVE` / `EXCLUDED` |
| 과제 상태 · 완료 유형 | `NOT_STARTED` / `IN_PROGRESS` / `COMPLETED` / `INCOMPLETE` · `PRE_SOLVED` / `ON_TIME` / `LATE` |
| 힌트 요청 `status` | `REQUESTED` / `GENERATING` / `READY` / `FALLBACK` / `BLOCKED` |
| GitHub 연동 · Sync Job | `CONNECTED` / `DISCONNECTED` · `PENDING` / `RUNNING` / `RETRY_WAITING` / `RATE_LIMITED` / `SUCCEEDED` / `FAILED` / `CANCELED` |

### **0.6 권한 요약**

| 서비스 | API 그룹 | 인증 조건 | 리소스 권한 확인 | 작성 |
| --- | --- | --- | --- | --- |
| member | GitHub 로그인·토큰 교환 | 공개 | — | E |
| member | 토큰 재발급·로그아웃 | Refresh Token 쿠키 / 로그인 필요 | 본인 토큰 | E |
| member | 내 정보·학습 프로필·태그 레벨·학습 통계 | 로그인 필요 (정보 수정·프로필 저장·태그 진입은 `SUSPENDED` 차단) | 본인 | E |
| member | 진단 테스트 | 로그인 필요 (`SUSPENDED` 차단) | 본인 응시 | E |
| member | 회원 관리자 | `ADMIN` | — | E |
| problem | 문제 목록·상세·통계·태그 조회 | 로그인 필요 | `GENERAL`·`PUBLIC`만 | B |
| problem | 풀이 공유·라인 댓글 | 로그인 필요 (`SUSPENDED` 차단) | 공유 대상 스터디 `APPROVED` 구성원 (조회 시점 재검증) | B |
| problem | 문제 관리자 | `ADMIN` | 목록은 전체, 상세·수정·상태 변경은 작성 관리자 | B |
| judge | 제출·실행·결과 조회 | 로그인 필요 (제출·실행은 `SUSPENDED` 차단) | 본인 `PRACTICE` 제출. 시험·대회 결과는 contest 경유 | B |
| judge | 채점 관리자 | `ADMIN` | 재채점은 `EXAM` 장애 실패 건만 | B |
| contest | 시험 생성·수정·취소 | 로그인 필요 (`SUSPENDED` 차단) | 해당 스터디 `LEADER`·`MANAGER` (study 동기 조회) | C |
| contest | 시험 참가·응시·결과 | 로그인 필요 (`SUSPENDED` 차단) | 등록 참가자, 등록 시 스터디 `APPROVED` 구성원 | C |
| contest | 시험 실패 재처리·작업 조회 | 로그인 필요 | `ADMIN` 또는 해당 시험 스터디 현재 `LEADER`·`MANAGER` | C |
| contest | 대회 조회·순위 | 공개 | — | C |
| contest | 대회 참가·응시 | 로그인 필요 (`SUSPENDED` 차단) | 등록·실제 입장 참가자 | C |
| contest | 시험·대회 관리자 | `ADMIN` | — | C |
| study | 스터디 탐색·조회 | 공개 (로그인 시 내 가입 상태 포함) | — | D |
| study | 내 스터디·가입·탈퇴 | 로그인 필요 (`SUSPENDED` 차단) | 본인 가입 | D |
| study | 가입 심사·강제 탈퇴·문제집·공지 | 로그인 필요 (`SUSPENDED` 차단) | 해당 스터디 `LEADER`·`MANAGER` | D |
| study | 스터디 설정·역할·위임·종료 | 로그인 필요 (`SUSPENDED` 차단) | 해당 스터디 `LEADER` | D |
| study | 문제집 조회·커뮤니티 | 로그인 필요 | 해당 스터디 `APPROVED` 구성원 | D |
| study | 스터디 추천 | 로그인 필요 | — | D |
| study | 스터디 관리자 | `ADMIN` | — | D |
| AI | 힌트 요청·조회 | 로그인 필요 (`SUSPENDED` 차단) | 본인 요청 | D |
| notification | 알림·SSE·수신 설정 | 로그인 필요 | 본인 알림 | A |
| integration | GitHub 연동·동기화 | 로그인 필요 (수동 동기화는 `SUSPENDED` 차단) | 본인 연동 | A |
| 공통 | 내부 API `/internal/v1/**` | 서비스 계정 | 호출 서비스별 허용 목록 | 공통 |

### **0.7 작성 규칙**

- 각 서비스 절은 `N.0 개요 → 외부 API → 관리자 API → 내부 API` 순서로 쓴다.
- 다른 서비스가 제공하는 내부 API는 제공 서비스 절에 정의하고, 호출하는 쪽은 **외부 의존**과 `N.x 사용하는 내부 API` 표에서 참조만 한다.
- 각 API는 아래 양식을 **모든 항목 빠짐없이** 따른다. 해당 내용이 없으면 "없음"으로 적는다. 다른 API와 형태가 같아도 표를 생략하지 않고, "○○와 같은 형식"이라는 설명은 **비고**에 적는다.
- 요청·응답 예시 JSON은 한 줄에 한 필드씩 풀어 쓴다.
- **응답 파라미터**는 리다이렉트 `Location` 등 응답에 실리는 Path·Query 값이다.

```
#### N.N.N API 이름
- **Method / URI:**
- **인증:** 공개 / 로그인 필요 / 로그인 필요 (`SUSPENDED` 차단) / `ADMIN` / 서비스 계정
- **Idempotency:**
- **Rate Limit:**
- **대응 테이블:**
- **설명:**
- **요청 헤더:** (표)
- **요청 파라미터:** (표 — Path·Query)
- **요청 바디:** (필드 표 + 예시)
- **응답 헤더:** (표)
- **응답 파라미터:** (표 — Path·Query)
- **응답 바디 (200 / 201 / 202 / 204):** (필드 표 + 예시)
- **에러:** `ERROR_CODE`(status) / 발생 조건
- **동시성/멱등 보장:**
- **발행:** `EventName` — 발행 시점
- **외부 의존:** `METHOD /internal/v1/...` (서비스) — 용도, 실패 시 처리
- **프론트 계약:**
- **비고:**
```

---

## 1. 인증·회원 (member)

> 작성: 담당 E · 서비스: `member-service`
> 

### 1.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/auth/**`, `/api/v1/members/**`, `/api/v1/diagnoses/**`, `/api/v1/admin/members/**` |
| 내부 경로 | `/internal/v1/members/**` |
| 소유 테이블 | 스키마 `member` (토큰은 Redis) |
| 로그인 | GitHub OAuth2 단일. 인가 범위 `read:user`, `user:email` |
| 토큰 | Access 1시간(응답 본문), Refresh 14일(`HttpOnly`·`Secure`·`SameSite=Strict` 쿠키), Redis 회전 |
| 레벨 | 태그별 `LV0`~`MASTER`, 저장값 증감 없이 재계산 |
| 이벤트 발행 | `MemberUpdated`(이벤트명 확정 필요), `LearningProfileUpdated`, `TagLevelChanged`, `MemberWithdrawn`, `MemberSuspended`, `DiagnosisSubmissionRequested`(확정 필요) |
| 이벤트 구독 | `SubmissionJudged`(레벨·성취도 재계산), `SubmissionFailed`(진단), `HintReady`(힌트 사용 감점, 확정 필요) |
| 외부 서비스 | GitHub OAuth (`github.com/login/oauth`, `api.github.com/user`) |

### 1.1 외부 API — 인증

#### 1.1.1 GitHub 로그인 시작

- **Method / URI:** `GET /api/v1/auth/github`
- **인증:** 공개
- **대응 테이블:** 없음 (OAuth `state`는 Redis, TTL 10분)
- **설명:** GitHub 인가 화면으로 리다이렉트한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 위치 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- | --- |
    | `redirectPath` | Query | string | N | `/` | 로그인 후 이동할 프론트 경로. 허용 목록의 상대 경로만 |
- **응답 (302):**
    
    
    | 헤더 | 값 |
    | --- | --- |
    | `Location` | `https://github.com/login/oauth/authorize?client_id=…&scope=read:user%20user:email&state=…` |
- **에러:** `COMMON_INVALID_REQUEST`(400) / 허용 목록 밖 `redirectPath`
- **외부 의존:** GitHub OAuth 인가 화면 (리다이렉트만, 서버 호출 없음)

#### 1.1.2 GitHub 로그인 콜백

- **Method / URI:** `GET /api/v1/auth/github/callback`
- **인증:** 공개
- **대응 테이블:** `members`, `outbox_events`
- **설명:** GitHub 인가 코드로 사용자 정보를 받아 회원을 조회·생성하고, 1회용 로그인 교환 코드(TTL 60초)를 발급해 프론트로 리다이렉트한다. 토큰을 URL에 싣지 않는다.
- **요청 파라미터:**
    
    
    | 파라미터 | 위치 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- | --- |
    | `code` | Query | string | Y | GitHub 인가 코드 |
    | `state` | Query | string | Y | GitHub 로그인 시작(1.1.1)에서 발급한 `state` |
- **응답 (302):** `Location: {frontendOrigin}/auth/callback?loginCode=…&redirectPath=/`
- **에러:**
    - `AUTH_OAUTH_FAILED`(502) / GitHub 응답 실패
    - `AUTH_TOKEN_INVALID`(401) / `state` 불일치·만료
- **동시성/멱등 보장:** 신규 가입은 `uk_members_github_id`로 중복 생성을 막는다. 위반 시 기존 회원으로 로그인.
- **발행:** `MemberUpdated` — 신규 가입 시 (`member_replicas` 원천, 이벤트명 확정 필요)
- **외부 의존:**
    - `POST https://github.com/login/oauth/access_token` (GitHub) — 인가 코드 교환. 타임아웃 3초, 실패 시 502
    - `GET https://api.github.com/user`, `/user/emails` (GitHub) — 프로필·이메일. 이메일 실패는 `email = null`로 진행

#### 1.1.3 로그인 코드 교환

- **Method / URI:** `POST /api/v1/auth/token`
- **인증:** 공개
- **대응 테이블:** `members` (토큰은 Redis)
- **설명:** 로그인 교환 코드로 Access Token을 발급하고 Refresh Token을 쿠키로 설정한다. 교환 코드는 1회만 사용한다.
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `loginCode` | string | Y | GitHub 로그인 콜백(1.1.2)이 전달한 1회용 코드 |
    
    ```json
    { "loginCode": "lc_8f2c…" }
    ```
    
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `accessToken` | string | JWT |
    | `expiresIn` | integer | 초 단위 수명 (3600) |
    | `member.memberId` · `nickname` · `role` · `status` | string | 회원 요약 |
    | `member.isNewMember` | boolean | 최초 로그인 여부 (학습 프로필 안내용) |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "accessToken": "eyJ…", "expiresIn": 3600,
        "member": { 
    	    "memberId": "7", 
    	    "nickname": "kim-dev", 
    	    "role": "USER", 
    	    "status": "ACTIVE", 
    	    "isNewMember": true 
        }
      }
    }
    ```
    
    | 응답 헤더 | 값 |
    | --- | --- |
    | `Set-Cookie` | `refreshToken=…; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600` |
- **에러:** `AUTH_LOGIN_CODE_INVALID`(401) / 코드 없음·만료·재사용
- **동시성/멱등 보장:** Redis `GETDEL`로 코드를 원자적으로 소비한다.

#### 1.1.4 토큰 재발급

- **Method / URI:** `POST /api/v1/auth/token/refresh`
- **인증:** Refresh Token 쿠키
- **대응 테이블:** 없음 (Redis)
- **설명:** Refresh Token을 회전(Rotation)하고 새 Access Token을 발급한다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Cookie` | Y | `refreshToken=…` | 1.1.3에서 설정한 쿠키 |
- **응답 (200):** `{ "accessToken": "eyJ…", "expiresIn": 3600 }` + 새 `Set-Cookie`
    
    ```json
    	{ 
    		"success": true, 
    		"message": null, 
    		"data": { 
    			"accessToken": "eyJ…", 
    			"expiresIn": 3600 
    		} 
    	}
    ```
    
- **에러:**
    - `AUTH_REFRESH_TOKEN_INVALID`(401) / 토큰 없음·만료·폐기
    - `AUTH_REFRESH_TOKEN_REUSED`(401) / 이미 교체된 토큰 재사용 → 해당 회원의 모든 Refresh Token 폐기
- **동시성/멱등 보장:** Redis에서 토큰 비교·교체를 원자적으로 수행(Lua). 동시 재발급 두 건 중 하나는 `REUSED`로 처리된다.

#### 1.1.5 로그아웃

- **Method / URI:** `POST /api/v1/auth/logout`
- **인증:** 로그인 필요
- **대응 테이블:** 없음 (Redis)
- **설명:** Refresh Token을 삭제하고, Access Token은 남은 수명 동안 차단 목록에 등록한다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | 차단 목록 등록 대상 |
    | `Cookie` | N | `refreshToken=…` | 삭제 대상 |
- **응답 (204):** `Set-Cookie: refreshToken=; Max-Age=0`

#### 1.1.6 개발용 로그인

- **Method / URI:** `POST /api/v1/auth/dev-login`
- **인증:** 공개 (`local`·`dev` 프로필에서만 라우팅)
- **대응 테이블:** `members`
- **설명:** 지정한 회원으로 토큰을 발급한다. `prod`·`staging`에서 활성화 설정이 감지되면 애플리케이션 기동을 실패시킨다.
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `memberId` | string | Y | 토큰을 발급할 회원 |
- **응답 (200):** 로그인 코드 교환(1.1.3)과 같다.
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `accessToken` | string | JWT |
    | `expiresIn` | integer | 초 단위 수명 (3600) |
    | `member.memberId` · `nickname` · `role` · `status` | string | 회원 요약 |
    | `member.isNewMember` | boolean | 최초 로그인 여부 (학습 프로필 안내용) |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "accessToken": "eyJ…", "expiresIn": 3600,
        "member": { 
    	    "memberId": "7", 
    	    "nickname": "kim-dev", 
    	    "role": "USER", 
    	    "status": "ACTIVE", 
    	    "isNewMember": true 
        }
      }
    }
    ```
    
    | 응답 헤더 | 값 |
    | --- | --- |
    | `Set-Cookie` | `refreshToken=…; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600` |
- **에러:** `MEMBER_NOT_FOUND`(404)

### 1.2 외부 API — 회원

#### 1.2.1 내 정보 조회

- **Method / URI:** `GET /api/v1/members/me`
- **인증:** 로그인 필요
- **대응 테이블:** `members`, `learning_profiles`
- **설명:** 내 계정 정보와 학습 프로필 입력 여부를 반환한다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | 로그인 토큰 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `memberId` · `githubLogin` · `nickname` | string | 계정 정보 |
    | `profileImageUrl` · `email` | string 또는 null | GitHub에서 받은 값 |
    | `role` · `status` | string | 0.5 참조 |
    | `hasLearningProfile` | boolean | 학습 프로필 입력 여부 |
    | `createdAt` | string (date-time) | 가입 시각 |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "memberId": "7", "githubLogin": "kim-dev", "nickname": "kim-dev",
        "profileImageUrl": "https://avatars.githubusercontent.com/u/…", "email": null,
        "role": "USER", "status": "ACTIVE", "hasLearningProfile": false,
        "createdAt": "2026-10-01T10:00:00+09:00"
      }
    }
    ```
    

#### 1.2.2 내 정보 수정

- **Method / URI:** `PATCH /api/v1/members/me`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `members`, `outbox_events`
- **설명:** 닉네임을 변경한다. 바꿀 필드만 보낸다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `nickname` | string | N | 2~50자 |
    
    ```json
      { "nickname": "kim-algo" }
    ```
    
    - `nickname` 2~50자
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "회원 정보가 수정되었습니다.", 
    	  "data": { 
    		  "memberId": "7", 
    		  "nickname": "kim-algo" 
    	  } 
      }
    ```
    
- **에러:**
    - `MEMBER_NICKNAME_INVALID`(400) /
    - `MEMBER_SUSPENDED`(403) /
- **동시성/멱등 보장:** `members.profile_version`을 1 올리고 같은 트랜잭션에서 Outbox 기록.
- **발행:** `MemberUpdated` — 닉네임·이미지 변경 시 (`member_replicas` 갱신, `profileVersion` = `sourceVersion`)

#### 1.2.3 회원 탈퇴

- **Method / URI:** `DELETE /api/v1/members/me`
- **인증:** 로그인 필요
- **대응 테이블:** `members`, `outbox_events`
- **설명:** 탈퇴 차단 조건을 확인한 뒤 `WITHDRAWN`으로 전이하고 표시 정보를 익명화한다. 토큰은 모두 폐기한다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **응답 (204)**
- **에러:**
    - `WITHDRAWAL_BLOCKED_ACTIVE_EXAM`(409) / 실제 입장한 시험·대회가 `FINALIZED` 전
    - `WITHDRAWAL_BLOCKED_STUDY_LEADER`(409) / `CLOSED`가 아닌 스터디의 스터디장 (위임 또는 종료 안내)
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503) / 차단 조건 조회 실패
- **외부 의존:**
    - `GET /internal/v1/participations/active?memberId=` (contest, 4.7.2) — 실제 입장한 미확정 시험·대회 여부. 실패 시 fail-closed
    - `GET /internal/v1/studies/memberships?memberId=&role=LEADER&activeOnly=true` (study, 5.7.2) — 스터디장 여부. 실패 시 fail-closed
- **발행:**
    - `MemberWithdrawn` — 탈퇴 트랜잭션 (전 서비스 정리)

### 1.3 외부 API — 학습 프로필

#### 1.3.1 학습 프로필 조회

- **Method / URI:** `GET /api/v1/members/me/learning-profile`
- **인증:** 로그인 필요
- **대응 테이블:** `learning_profiles`, `member_tag_levels`
- **설명:** 추천 입력값과 태그별 레벨 요약을 반환한다. 미입력이면 `data`가 `null`이다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `goal` | string | 학습 목표 Enum |
    | `preferredLanguages` | string[] | `java` / `python3` / `cpp` |
    | `activeTimeSlots` | string[] | 활동 시간대 Enum |
    | `tagLevels[]` | object[] | `tagId`, `tagName`, `level` |
    | `profileVersion` | string | 변경 순번 |
    | `updatedAt` | string (date-time) | 마지막 변경 |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "goal": "EMPLOYMENT", "preferredLanguages": ["java"], "activeTimeSlots": ["EVENING"],
        "tagLevels": [ { "tagId": "3", "tagName": "해시", "level": "LV2" } ],
        "profileVersion": "4", "updatedAt": "2026-10-02T21:00:00+09:00"
      }
    }
    
    ```
    
    - `preferredLanguages`·`activeTimeSlots` 단일/배열과 값 체계는 미결정(배열 초안)

#### 1.3.2 학습 프로필 저장

- **Method / URI:** `PUT /api/v1/members/me/learning-profile`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `learning_profiles`, `outbox_events`
- **설명:** 학습 프로필 전체를 저장한다. 최초 입력이면 생성한다. 값이 바뀐 경우에만 `profile_version`을 올리고 이벤트를 발행한다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `goal` | string | Y | 학습 목표 Enum |
    | `preferredLanguages` | string[] | Y | 1개 이상 |
    | `activeTimeSlots` | string[] | Y | 1개 이상 |
    
    ```json
    { "goal": "EMPLOYMENT", "preferredLanguages": ["java"], "activeTimeSlots": ["EVENING"] }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "학습 프로필이 저장되었습니다.", 
    	  "data": { 
    		  "profileVersion": "5" 
    	  } 
      }
    ```
    
- **에러:**
    - `COMMON_INVALID_REQUEST`(400) /
    - `MEMBER_SUSPENDED`(403) /
- **발행:** `LearningProfileUpdated` — 최초 입력·값 변경 시 (전체 상태 + `profileVersion`)

### 1.4 외부 API — 태그 레벨·성취도

#### 1.4.1 태그 레벨 목록

- **Method / URI:** `GET /api/v1/members/me/tag-levels`
- **인증:** 로그인 필요
- **대응 테이블:** `member_tag_levels`, `member_tag_achievements`
- **설명:** 진입한 태그의 레벨·성취도와 진단 응시 가능 여부를 반환한다. 진입하지 않은 태그를 `LV0`으로 그리려면 태그 목록 조회(2.5.1)와 합친다. 페이지네이션 없음(태그 수 고정).
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `content[].tagId` · `level` | string | 태그·현재 레벨 |
    | `content[].accessibleMaxDifficulty` | integer | 연습 제출 가능한 최대 난이도 |
    | `content[].achievements[]` | object[] | 난이도별 `solvedCount`, `achievementRate`, `band` |
    | `content[].diagnosis` | object | `isEligible`, `cooldownEndsAt`, `inProgressDiagnosisId` |
    | `content[].nextPromotion` | object 또는 null | 다음 승급 기준 |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "content": [
          {
            "tagId": "3", "level": "LV2", "accessibleMaxDifficulty": 2,
            "achievements": [
              { "difficulty": 1, "solvedCount": 6, "achievementRate": 83.33, "band": "GOOD" },
              { "difficulty": 2, "solvedCount": 2, "achievementRate": 50.00, "band": "WEAK" }
            ],
            "diagnosis": { "isEligible": true, "cooldownEndsAt": null, "inProgressDiagnosisId": null },
            "nextPromotion": { "targetLevel": "LV3", "requiredSolvedCount": 6, "requiredAchievementRate": 70.00 }
          }
        ]
      }
    }
    ```
    
    - `band`: `GOOD`(80% 이상) / `NORMAL`(60~79%) / `WEAK`(0~59%, 약점). 승급 기준값은 설정값으로 관리한다.

#### 1.4.2 태그 진입 (진단 건너뛰기)

- **Method / URI:** `POST /api/v1/members/me/tag-levels`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `member_tag_levels`, `member_level_histories`, `learning_profiles`, `outbox_events`
- **설명:** 태그에 처음 진입하며 진단 없이 `LV1`로 시작한다. 쿨타임을 걸지 않는다. 진단을 선택하면 이 API 대신 진단 시작(1.5.1)을 호출한다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `tagId` | string | Y | 진입할 태그 |
    
    ```json
      { "tagId": "3" }
    ```
    
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "Lv.1로 시작합니다.", 
    	  "data": { 
    		  "tagId": "3", 
    		  "level": "LV1" 
    	  } 
      }
    ```
    
- **에러:**
    - `LEVEL_TAG_NOT_FOUND`(404), /
    - `LEVEL_ALREADY_ENTERED`(409) /
    - `MEMBER_SUSPENDED`(403) /
- **동시성/멱등 보장:** `uk_member_tag_levels_member_tag`. 위반 시 `LEVEL_ALREADY_ENTERED`.
- **발행:** `LearningProfileUpdated` — 태그 레벨 확정 (`tagLevels` 갱신)
- **외부 의존:** 태그 존재 확인 방식(태그 복제본 또는 problem 내부 조회)은 확정 필요

#### 1.4.3 레벨 변경 근거 조회

- **Method / URI:** `GET /api/v1/members/me/tag-levels/{tagId}/histories`
- **인증:** 로그인 필요
- **대응 테이블:** `member_level_histories`, `member_tag_levels`
- **설명:** 태그의 레벨 변경 이력(진단·승급·재채점 하향)을 최신순으로 반환한다.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **요청 파라미터:**
    
    
    | 파라미터 | 위치 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- | --- |
    | `tagId` | Path | string | Y | — | 태그 ID |
    | `page` | Query | int | N | 0 |  |
    | `size` | Query | int | N | 20 | 최대 100 |
    | `sort` | Query | string | N | `createdAt,desc` | 허용: `createdAt` |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `content[].fromLevel` · `toLevel` | string | 변경 전후 |
    | `content[].reasonType` | string | `ENTRY_SKIP` / `DIAGNOSIS` / `PROMOTION` / `REJUDGE` |
    | `content[].sourceSubmissionId` · `diagnosisId` | string 또는 null | 근거 |
    | `content[].createdAt` | string (date-time) |  |
    
    ```json
    {
      "success": true, "message": null,
      "data": {
        "content": [ { "fromLevel": "LV1", "toLevel": "LV2", "reasonType": "PROMOTION", "sourceSubmissionId": "91023", "diagnosisId": null, "createdAt": "2026-10-02T21:00:00+09:00" } ],
        "page": 0, "size": 20, "totalElements": 2, "totalPages": 1, "hasNext": false
      }
    }
    
    ```
    
    - `reasonType`: `ENTRY_SKIP` / `DIAGNOSIS` / `PROMOTION` / `REJUDGE`

#### 1.4.4 약점 태그 조회

- **Method / URI:** `GET /api/v1/members/me/weak-tags`
- **인증:** 로그인 필요
- **대응 테이블:** `member_tag_achievements`
- **설명:** 성취도 부족(0~59%) 구간인 태그·난이도를 성취도 오름차순으로 반환한다(T5-08, P3). 페이지네이션 없음.
- **요청 헤더:** `Authorization: Bearer <accessToken>` (Y)
- **응답 (200):**
    
    ```json
    { "success": true, "message": null, "data": { "content": [ { "tagId": "5", "difficulty": 2, "achievementRate": 40.00, "solvedCount": 2 } ] } }
    ```
    

### 1.5 외부 API — 진단 테스트

> `member-service`가 접수하고 채점을 요청하는 초안. **진단 제출 접수 경로·이벤트명 결정 필요.**
> 

#### 1.5.1 진단 시작

- **Method / URI:** `POST /api/v1/diagnoses`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `diagnosis_attempts`, `diagnosis_problems`, `member_tag_levels`
- **설명:** 태그의 난이도 1·2·3 공개 문제 중 무작위로 1문항씩 출제하고 60분 진단을 시작한다. 태그 첫 진입이면 레벨은 `LV0`으로 두고 진단 결과로 확정한다.
- **요청 바디:**
    
    ```json
      { "tagId": "3" }
    ```
    
- **응답 (201):**
    
    ```json
      {
        "success": true,
        "message": "진단을 시작합니다.",
        "data": {
          "diagnosisId": "120",
          "tagId": "3",
          "startedAt": "2026-10-03T20:00:00+09:00",
          "endsAt": "2026-10-03T21:00:00+09:00",
          "serverNow": "2026-10-03T20:00:00+09:00",
          "problems": [
            { "problemId": "301", "problemRevisionId": "1001", "difficulty": 1, "title": "두 수의 합" },
            { "problemId": "315", "problemRevisionId": "1040", "difficulty": 2, "title": "…" },
            { "problemId": "330", "problemRevisionId": "1092", "difficulty": 3, "title": "…" }
          ]
        }
      }
    ```
    
- **에러:**
    - `LEVEL_TAG_NOT_FOUND`(404)
    - `DIAGNOSIS_NOT_ELIGIBLE`(409) / 레벨 `LV3`·`MASTER`
    - `DIAGNOSIS_COOLDOWN`(409) / 직전 종료 + 3일 전 (`Retry-After` 포함)
    - `DIAGNOSIS_ALREADY_IN_PROGRESS`(409)
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503) / 출제 후보 조회 실패
    - `MEMBER_SUSPENDED`(403)
- **동시성/멱등 보장:** 생성 컬럼 `active_tag_id` + `uk_diagnosis_attempts_member_active_tag`. 동시 시작 두 건 중 하나는 `DIAGNOSIS_ALREADY_IN_PROGRESS`.
- **외부 의존:**
    - `GET /internal/v1/problems/diagnosis-candidates?tagId=` (problem) — 난이도별 공개 문제 1개씩 무작위 반환. 실패 시 fail-closed.

#### 1.5.2 진단 상세·복구

- **Method / URI:** `GET /api/v1/diagnoses/{diagnosisId}`
- **인증:** 로그인 필요 (본인)
- **대응 테이블:** `diagnosis_attempts`, `diagnosis_problems`, `diagnosis_drafts`, `diagnosis_submissions`
- **설명:** 재진입 시 남은 시간과 문항별 서버 Draft(`seq`)·최종 제출 상태를 반환한다. 클라이언트는 LocalStorage와 `seq`를 비교해 큰 쪽으로 복구한다. 종료 후에는 결과를 함께 반환한다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "diagnosisId": "120",
          "tagId": "3",
          "status": "IN_PROGRESS",
          "endsAt": "2026-10-03T21:00:00+09:00",
          "serverNow": "2026-10-03T20:21:10+09:00",
          "problems": [
            {
              "problemId": "301", 
              "problemRevisionId": "1001", 
              "difficulty": 1,
              "draft": { 
    	          "language": "python3", 
    	          "sourceCode": "def solution(a, b):\n    ...", 
    	          "seq": "14", 
    	          "savedAt": "2026-10-03T20:20:58+09:00" 
    	        },
              "lastSubmission": { 
    	          "submissionStatus": "JUDGED", 
    	          "draftSeq": "12" 
              }
            }
          ],
          "result": null
        }
    }
    ```
    
    - `result`: `SCORED`이면 `{ "score": 3.00, "resultLevel": "LV2", "appliedLevel": "LV2" }`. 판정은 진단 종료 후에만 공개.
    - 문제 본문은 문제 상세 조회(2.1.2) 대신 `GET /api/v1/diagnoses/{diagnosisId}/problems/{problemId}` 진단 문제 본문 조회(1.5.3)로 조회
- **에러:**
    - `DIAGNOSIS_NOT_FOUND`(404)

#### 1.5.3 진단 문제 본문 조회

- **Method / URI:** `GET /api/v1/diagnoses/{diagnosisId}/problems/{problemId}`
- **인증:** 로그인 필요 (본인)
- **대응 테이블:** `diagnosis_problems`
- **설명:** 진단에 고정한 회차의 본문·함수 명세·공개 예제를 반환한다. 레벨 접근 제한을 적용하지 않는다.
- **응답 (200):** 태그 수정(2.5.3) 응답 구조와 같다.
    
    ```json
      { 
    	  "success": true, 
    	  "message": "태그가 수정되었습니다.", 
    	  "data": { 
    		  "tagId": "2", 
    		  "name": "그리디" 
    	  } 
      }
    ```
    
- **에러:**
    - `DIAGNOSIS_NOT_FOUND`(404), `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:**
    - `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content` (problem) — 고정 회차 본문. 실패 시 503

#### 1.5.4 진단 Draft 저장

- **Method / URI:** `PUT /api/v1/diagnoses/{diagnosisId}/problems/{problemId}/draft`
- **인증:** 로그인 필요 (본인)
- **Rate Limit:** 응시자당 초당 1회 (초과 요청은 마지막 요청만 반영)
- **대응 테이블:** `diagnosis_drafts`
- **설명:** 작성 중 코드를 저장한다. 입력이 2초 멈추면, 입력 중에도 최대 10초마다 호출한다.
- **요청 바디:**
    
    ```json
      { 
    	  "language": "python3", 
    	  "sourceCode": "def solution(a, b):\n    return a + b\n", 
    	  "seq": "15" 
      }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": null, 
    	  "data": { 
    		  "applied": true, 
    		  "seq": "15", 
    		  "savedAt": "2026-10-03T20:21:12+09:00" 
    	  } 
      }
    ```
    
    - 늦게 도착한 이전 Draft는 `applied: false`와 서버 `seq`를 반환한다.
- **에러:**
    - `DIAGNOSIS_NOT_FOUND`(404), `DIAGNOSIS_CLOSED`(409), `CODE_TOO_LARGE`(413)
- **동시성/멱등 보장:** `WHERE seq < :incomingSeq` 조건부 UPDATE. 서버 접수 시각이 `ends_at`을 넘으면 거절.

#### 1.5.5 진단 문항 제출

- **Method / URI:** `POST /api/v1/diagnoses/{diagnosisId}/problems/{problemId}/submissions`
- **인증:** 로그인 필요 (본인)
- **Idempotency:** 같은 `(진단, 문항, seq)`는 기존 접수를 반환
- **Rate Limit:** 동일 문항 5초 간격
- **대응 테이블:** `diagnosis_drafts`, `diagnosis_submissions`, `outbox_events`
- **설명:** 해당 코드·`seq`로 Draft를 반영하고 제출을 접수한 뒤 채점을 요청한다. 판정은 진단 종료 후 공개한다.
- **요청 바디:**
    
    ```json
      { 
    	  "language": "python3", 
    	  "sourceCode": "def solution(a, b):\n    return a + b\n", 
    	  "seq": "16" 
      }
    ```
    
- **응답 (202):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "제출이 접수되었습니다.", 
    	  "data": { 
    		  "diagnosisSubmissionId": "880", 
    		  "status": "ACCEPTED", 
    		  "receivedAt": "2026-10-03T20:22:00+09:00" 
    	  } 
      }
    ```
    
- **에러:**
    - `DIAGNOSIS_NOT_FOUND`(404), `DIAGNOSIS_CLOSED`(409), `UNSUPPORTED_LANGUAGE`(400), `CODE_TOO_LARGE`(413), `SUBMISSION_RATE_LIMITED`(429)
- **동시성/멱등 보장:** `uk_diagnosis_submissions_attempt_problem_seq`. Draft 반영·제출·Outbox를 한 트랜잭션에 저장.
- **발행:**
    - `DiagnosisSubmissionRequested` — 접수 트랜잭션 (judge 채점 요청, 경로·이벤트명 확정 필요)

#### 1.5.6 진단 최종 제출 (종료)

- **Method / URI:** `POST /api/v1/diagnoses/{diagnosisId}/finish`
- **인증:** 로그인 필요 (본인)
- **대응 테이블:** `diagnosis_attempts`, `diagnosis_drafts`, `diagnosis_submissions`, `outbox_events`
- **설명:** 진단을 종료한다. 마지막 제출 이후 변경된 Draft가 있는 문항은 자동 제출한다. 이후 저장·제출은 거절한다. 제한 시각 도달 시에는 스케줄러가 같은 처리를 한다.
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "진단이 종료되었습니다. 채점이 끝나면 결과를 알려드립니다.", 
    	  "data": { 
    		  "diagnosisId": "120", 
    		  "status": "CLOSED", 
    		  "closedAt": "2026-10-03T20:40:00+09:00" 
    	  } 
      }
    ```
    
- **에러:**
    - `DIAGNOSIS_NOT_FOUND`(404), `DIAGNOSIS_CLOSED`(409)
- **동시성/멱등 보장:** `WHERE status = 'IN_PROGRESS'` 조건부 UPDATE. 수동 종료와 만료 스케줄러가 경합해도 1회만 종료된다.
- **발행:**
    - `DiagnosisSubmissionRequested` — 자동 제출 문항마다
    - `TagLevelChanged`·`LearningProfileUpdated` — 모든 제출 채점 후 `SCORED` 전이 시
- **프론트 계약:**
    - 결과 확정은 SSE `LEVEL_CHANGED`(진단 결과) 알림으로 받고 진단 상세·복구(1.5.2)로 결과를 조회한다.

#### 1.5.7 진단 이력

- **Method / URI:** `GET /api/v1/diagnoses`
- **인증:** 로그인 필요
- **대응 테이블:** `diagnosis_attempts`
- **설명:** 본인의 진단 응시 이력을 반환한다. 재응시 결과가 현재 레벨보다 낮아도 점수 이력으로 보여준다.
- **요청 파라미터:**
허용 정렬 필드: `startedAt` (기본 `startedAt,desc`)
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `tagId` | string | N | — | 태그 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { 
    		      "diagnosisId": "120", 
    		      "tagId": "3", 
    		      "status": "SCORED", 
    		      "score": 3.00, 
    		      "resultLevel": "LV2", 
    		      "closeReason": "SUBMITTED", 
    		      "startedAt": "2026-10-03T20:00:00+09:00", 
    		      "closedAt": "2026-10-03T20:40:00+09:00" 
    	      } 
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    

### 1.6 관리자 API — 회원

#### 1.6.1 회원 목록 조회

- **Method / URI:** `GET /api/v1/admin/members`
- **인증:** `ADMIN`
- **대응 테이블:** `members`
- **요청 파라미터:**
허용 정렬 필드: `createdAt` (기본 `createdAt,desc`)
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `keyword` | string | N | — | 닉네임·GitHub 로그인명 검색 |
    | `status` | string | N | — | `ACTIVE` / `SUSPENDED` / `WITHDRAWN` |
    | `role` | string | N | — | `USER` / `ADMIN` |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { 
    		      "memberId": "7", 
    		      "nickname": "kim-dev", 
    		      "githubLogin": "kim-dev", 
    		      "role": "USER", 
    		      "status": "ACTIVE", 
    		      "createdAt": "2026-10-01T10:00:00+09:00" 
    	      } 
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    

#### 1.6.2 회원 정지·해제

- **Method / URI:** `PATCH /api/v1/admin/members/{memberId}/status`
- **인증:** `ADMIN`
- **대응 테이블:** `members`, `audit_logs`, `outbox_events`
- **설명:** `ACTIVE ↔ SUSPENDED`로 전이하고 사유·집행자를 감사 로그에 남긴다.
    - **진행 중 시험·대회의 기존 제출 처리는 미결정.**
- **요청 바디:**
    
    ```json
      { 
    	  "status": "SUSPENDED", 
    	  "reason": "부정 행위 신고 확인" 
      }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "회원 상태가 변경되었습니다.", 
    	  "data": { 
    		  "memberId": "7", 
    		  "status": "SUSPENDED" 
    	  } 
      }
    ```
    
- **에러:**
    - `MEMBER_NOT_FOUND`(404), `COMMON_INVALID_REQUEST`(400) / `WITHDRAWN` 대상 또는 같은 상태
- **발행:**
    - `MemberSuspended` — 정지·해제 시 (`suspended: true/false`, 순번 포함)

#### 1.6.3 회원 권한 변경

- **Method / URI:** `PATCH /api/v1/admin/members/{memberId}/role`
- **인증:** `ADMIN`
- **대응 테이블:** `members`, `audit_logs`
- **요청 바디:**
    
    ```json
      { 
    	  "role": "ADMIN", 
    	  "reason": "운영진 합류" 
      }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "권한이 변경되었습니다.", 
    	  "data": { 
    		  "memberId": "7", 
    		  "role": "ADMIN" 
    	  } 
      }
    ```
    
- **에러:**
    - `MEMBER_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403) / 본인 권한 변경
- **동시성/멱등 보장:** 권한 변경은 다음 토큰 발급부터 반영된다(기존 Access Token 만료 대기 최대 1시간).

### 1.7 내부 API

#### 1.7.1 회원 상태 조회 (내부)

- **Method / URI:** `GET /internal/v1/members/{memberId}/status`
- **인증:** 서비스 계정
- **대응 테이블:** `members`
- **설명:** `SUSPENDED` 차단 확인용. 각 서비스는 `MemberSuspended` 복제본을 우선 쓰고, 복제본이 없을 때만 호출한다.
- **응답 (200):**
    
    ```json
      { 
    	  "memberId": "7", 
    	  "status": "ACTIVE", 
    	  "role": "USER" 
      }
    ```
    

#### 1.7.2 태그 레벨 조회 (내부)

- **Method / URI:** `GET /internal/v1/members/{memberId}/tag-levels`
- **인증:** 서비스 계정
- **대응 테이블:** `member_tag_levels`
- **설명:** judge가 `PRACTICE` 제출 전 레벨 접근 제한(난이도 ≤ 레벨)을 확인할 때 호출한다.
    - **다중 태그 문제의 판정 기준(최고·최저 레벨)은 확정 필요.**
- **응답 (200):**
    
    ```json
      { 
    	  "memberId": "7", 
    	  "tagLevels": [ 
    		  { 
    			  "tagId": "3", 
    			  "level": "LV2" 
    		  } 
    	  ] 
      }
    ```
    
- **실패 시 처리:** judge는 fail-closed(`COMMON_DEPENDENCY_UNAVAILABLE`)

---

## 2. 문제 (problem)

> 작성: 담당 B · 서비스: `problem-service`
> 

### 2.1 외부 API — 문제 조회

#### 2.1.1 문제 목록 조회

- **Method / URI:** `GET /api/v1/problems`
- **인증:** 로그인 필요
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tags`, `tags`, `member_problem_statuses`
- **설명:** 일반 공개 문제(`GENERAL`·`PUBLIC`)의 현재 회차를 조건으로 검색한다. 시험 전용 문제는 제외한다. 레벨 접근 가능 여부는 프론트가 태그 레벨 목록(1.4.1)의 태그 레벨과 비교해 표시한다(제출 시 judge가 검증).
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `keyword` | string | N | — | 제목 검색 |
    | `difficulty` | int | N | — | 1~3 |
    | `tagIds` | string[] | N | — | 태그 ID 목록, 하나라도 포함하면 일치(OR). `tagIds=1&tagIds=3` |
    | `solvedStatus` | string | N | — | `SOLVED` / `UNSOLVED` |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 | 최대 100 |
    | `sort` | string | N | `publishedAt,desc` |  |
    
    허용 정렬 필드: `publishedAt`, `difficulty`, `problemId`
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            {
              "problemId": "301",
              "problemRevisionId": "1001",
              "revisionNumber": 1,
              "title": "두 수의 합",
              "difficulty": 1,
              "tags": [ 
    	          { 
    		          "tagId": "1", 
    		          "name": "구현" 
    	          } 
              ],
              "publishedAt": "2026-09-27T10:00:00+09:00",
              "solvedStatus": "UNSOLVED"
            }
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    
    - `publishedAt`: 현재 회차 등록 시각(`problem_revisions.created_at`)

#### 2.1.2 문제 상세 조회

- **Method / URI:** `GET /api/v1/problems/{problemId}`
- **인증:** 로그인 필요
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_revision_tags`, `tags`, `problem_revision_tests`, `problem_test_assets`, `member_problem_statuses`
- **설명:** 현재 공개 회차의 본문·함수 명세·실행 제한·기본 코드·공개 예제를 조회한다. 숨김 테스트와 과거 회차는 제공하지 않는다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "problemId": "301",
          "problemRevisionId": "1001",
          "revisionNumber": 1,
          "title": "두 수의 합",
          "difficulty": 1,
          "tags": [ 
    	      { 
    		      "tagId": "1", 
    		      "name": "구현" 
    	      } 
          ],
          "publishedAt": "2026-09-27T10:00:00+09:00",
          "solvedStatus": "UNSOLVED",
          "description": "## 문제\n두 정수 a와 b의 합을 반환하세요.\n\n![덧셈 예시](image:701)",
          "constraints": "- 입력 범위: -1000 <= a, b <= 1000",
          "examples": "| a | b | 반환값 |\n|---|---|---|\n| 1 | 2 | 3 |",
          "functionSpec": {
            "name": "solution",
            "parameters": [ 
    	        { 
    		        "name": "a", 
    		        "type": { "kind": "INT32" } 
    	        }, 
    	        { 
    		        "name": "b", 
    		        "type": { "kind": "INT32" } 
    	        } 
            ],
            "returnType": { "kind": "INT32" }
          },
          "executionLimits": { 
    	      "timeMs": 2000, 
    	      "memoryKb": 262144, 
    	      "wallTimeMs": 7000, 
    	      "outputLimitKb": 64 
          },
          "starterCodes": [ 
    	      { 
    		      "language": "python3", 
    		      "sourceCode": "def solution(a, b):\n    pass\n" 
    	      } 
          ],
          "samples": [ 
    	      { 
    		      "testAssetId": "501", 
    		      "argumentTexts": { "a": "1", "b": "2" }, 
    		      "expectedReturnText": "3" 
    	      } 
          ]
        }
      }
    ```
    
    - `executionLimits`: 모든 지원 언어에 같은 값을 적용한다. `wallTimeMs = timeMs + 5초`
- **에러:**
    - `PROBLEM_NOT_FOUND`(404), `PROBLEM_NOT_AVAILABLE`(404)

#### 2.1.3 일반 문제 본문 이미지 조회

- **Method / URI:** `GET /api/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}`
- **인증:** 로그인 필요
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_body_images`, `problem_images`
- **설명:** 현재 `GENERAL`·`PUBLIC`인 문제의 지정 회차 본문에 연결된 이미지를 반환한다. 최신 회차 여부는 검사하지 않아, 풀던 중 새 회차가 등록돼도 원래 회차 이미지를 볼 수 있다.
- **응답 (200):** 이미지 바이너리 (`Content-Type: image/png` 등, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`)
- **에러:**
    - `PROBLEM_NOT_FOUND`(404) / 미존재·열람 불가·연결 불일치

### 2.2 외부 API — 스터디 풀이 공유·리뷰

> 공유 시점의 코드·언어·회차를 스냅샷으로 저장한다(ERD `solution_shares`). 
1차 범위는 `PRACTICE` AC 제출, 시험·대회 제출은 `FINALIZED` 이후 확정 필요.
> 

#### 2.2.1 풀이 공유

- **Method / URI:** `POST /api/v1/solution-shares`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `solution_shares`, `study_membership_replicas`
- **설명:** 본인의 AC 제출 한 건을 본인이 속한 스터디 하나에 공유한다.
- **요청 바디:**
    
    ```json
      { "submissionId": "91023", "studyId": "12" }
    ```
    
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "풀이를 공유했습니다.", 
    	  "data": { "solutionShareId": "450" } 
      }
    ```
    
- **에러:**
    - `SOLUTION_SHARE_NOT_ALLOWED`(409) / 본인 제출이 아니거나 `PRACTICE` AC가 아님
    - `SOLUTION_ALREADY_SHARED`(409)
    - `AUTH_ACCESS_DENIED`(403) / 해당 스터디 `APPROVED` 구성원이 아님
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **동시성/멱등 보장:** 생성 컬럼 `active_submission_id` + `uk_solution_shares_study_active_submission`.
- **외부 의존:**
    - `GET /internal/v1/submissions/{submissionId}/source?purpose=SOLUTION_SHARE&memberId=` (judge) — 소유·AC 확인 후 코드 스냅샷. 실패 시 fail-closed

#### 2.2.2 스터디 공유 풀이 목록

- **Method / URI:** `GET /api/v1/solution-shares`
- **인증:** 로그인 필요
- **대응 테이블:** `solution_shares`, `study_membership_replicas`, `member_replicas`
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `studyId` | string | Y | — | 스터디 ID |
    | `problemId` | string | N | — | 문제 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `sharedAt` (기본 `sharedAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { 
    		      "solutionShareId": "450", 
    		      "problemId": "301", 
    		      "revisionNumber": 1, 
    		      "language": "python3", 
    		      "author": { 
    			      "memberId": "7", 
    			      "nickname": "kim-dev" 
    		      }, 
    		      "commentCount": 3, 
    		      "sharedAt": "2026-10-02T22:00:00+09:00" 
    	      } 
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403) / 해당 스터디 `APPROVED` 구성원이 아님

#### 2.2.3 공유 풀이 상세

- **Method / URI:** `GET /api/v1/solution-shares/{solutionShareId}`
- **인증:** 로그인 필요
- **대응 테이블:** `solution_shares`, `study_membership_replicas`, `member_replicas`
- **설명:** 공유 코드 스냅샷을 반환한다. 조회 시점마다 공유 스터디 구성원 여부를 다시 확인한다. 숨김 테스트는 공개하지 않는다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "solutionShareId": "450", 
          "studyId": "12", 
          "problemId": "301", 
          "problemRevisionId": "1001", 
          "revisionNumber": 1,
          "language": "python3", 
          "sourceCode": "def solution(a, b):\n    return a + b\n",
          "author": { 
    	      "memberId": "7", 
    	      "nickname": "kim-dev" 
          }, 
          "sharedAt": "2026-10-02T22:00:00+09:00"
        }
      }
    ```
    
- **에러:**
    - `SOLUTION_SHARE_NOT_FOUND`(404) / 없음·공유 취소·열람 권한 없음

#### 2.2.4 공유 취소

- **Method / URI:** `DELETE /api/v1/solution-shares/{solutionShareId}`
- **인증:** 로그인 필요 (공유자 본인)
- **대응 테이블:** `solution_shares`
- **응답 (204)**
- **에러:**
    - `SOLUTION_SHARE_NOT_FOUND`(404)
- **동시성/멱등 보장:** `WHERE status = 'SHARED'` 조건부 UPDATE → `CANCELED`.

#### 2.2.5 풀이 비교 (Diff)

- **Method / URI:** `GET /api/v1/solution-shares/diff`
- **인증:** 로그인 필요
- **대응 테이블:** `solution_shares`
- **설명:** 같은 문제의 공유 풀이 두 개 또는 본인 제출과 공유 풀이를 비교할 원문을 반환한다. Diff 계산은 클라이언트(Monaco Diff)가 한다. 회차가 다르면 `isRevisionDifferent: true`.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `baseShareId` | string | N | — | 기준 공유 풀이 (`baseSubmissionId`와 택1) |
    | `baseSubmissionId` | string | N | — | 기준 본인 제출 |
    | `targetShareId` | string | Y | — | 비교 대상 공유 풀이 |
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "base": { "language": "python3", "sourceCode": "…", "revisionNumber": 1 },
          "target": { "language": "java", "sourceCode": "…", "revisionNumber": 2 },
          "isRevisionDifferent": true
        }
      }
    ```
    
- **에러:**
    - `SOLUTION_SHARE_NOT_FOUND`(404), `COMMON_INVALID_REQUEST`(400) / 서로 다른 문제
- **외부 의존:**
    - `GET /internal/v1/submissions/{submissionId}/source?purpose=SOLUTION_SHARE&memberId=` (judge) — `baseSubmissionId` 사용 시

#### 2.2.6 라인 댓글 목록

- **Method / URI:** `GET /api/v1/solution-shares/{solutionShareId}/comments`
- **인증:** 로그인 필요 (공유 풀이 열람 권한)
- **대응 테이블:** `solution_comments`, `member_replicas`
- **설명:** 라인 번호 오름차순 → 작성 시각 오름차순으로 반환한다. 페이지네이션 없음.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": { 
    	    "content": [ 
    		    { 
    			    "commentId": "900", 
    			    "lineNumber": 2, 
    			    "content": "여기서 overflow 가능성은?", 
    			    "author": { 
    				    "memberId": "8", 
    				    "nickname": "lee" 
    			    }, 
    			    "status": "VISIBLE", 
    			    "createdAt": "2026-10-02T22:10:00+09:00" 
    		    } 
    	    ] 
        }
      }
    ```
    
    - `DELETED` 댓글은 `content: null`로 자리만 남긴다.

#### 2.2.7 라인 댓글 작성

- **Method / URI:** `POST /api/v1/solution-shares/{solutionShareId}/comments`
- **인증:** 로그인 필요 (`SUSPENDED` 차단, 열람 권한)
- **대응 테이블:** `solution_comments`
- **요청 바디:**
    
    ```json
      { "lineNumber": 2, "content": "여기서 overflow 가능성은?" }
    ```
    
    - `lineNumber`는 1 이상, 코드 줄 수 이하
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "댓글이 작성되었습니다.", 
    	  "data": { "commentId": "900" } 
      }
    ```
    
- **에러:**
    - `SOLUTION_SHARE_NOT_FOUND`(404), `COMMON_INVALID_REQUEST`(400)

#### 2.2.8 라인 댓글 수정·삭제

- **Method / URI:** `PATCH /api/v1/solution-shares/{solutionShareId}/comments/{commentId}`, `DELETE …/comments/{commentId}`
- **인증:** 로그인 필요 (작성자 본인)
- **대응 테이블:** `solution_comments`
- **요청 바디 (PATCH):**
    
    ```json
      { "content": "수정한 내용" }
    ```
    
- **응답:**
    - PATCH `200` `{ "commentId": "900" }`
    - DELETE `204` (`status = DELETED`)
- **에러:**
    - `SOLUTION_COMMENT_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403)

### 2.3 관리자 API — 문제 작성·등록·수정

#### 2.3.0 문제·테스트 공통 데이터 형식

**등록(2.3.1)·새 회차(2.3.4) 요청 필드**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `scope` | string | 최초 등록만 | `GENERAL` / `EXAM_ONLY`. 이후 변경하지 않음 |
| `rowVersion` | int | 새 회차 등록만 | 조회 당시 문제의 낙관적 락 값 |
| `title` | string | Y | 1~200자 |
| `description` | string | Y | 문제 설명 Markdown. 이미지는 `![설명](image:{imageId})` |
| `constraints` | string | Y | 제한 사항 Markdown |
| `examples` | string | Y | 입출력 예시·해설 Markdown (채점 테스트와 별개) |
| `difficulty` | int | Y | 1~3 |
| `tagIds` | string[] | Y | 태그 ID 목록. 없으면 빈 배열. 존재하지 않는·중복 ID 거절 |
| `functionSpec` | object | Y | 함수명(`solution` 고정)·매개변수·반환 타입(TypeSpec) |
| `executionLimits` | object 또는 null | N | 생략·null 항목은 등록 시점 공통값 적용 |
| `testCases` | object[] | Y | 공개(`isSample=true`) 1개 이상·숨김 1개 이상(정책 §4) |
- 일부만 편집해도 전체 본문·테스트를 보낸다. 서버가 기존 파일과 비교해 바뀌지 않은 본문·테스트 파일은 재사용한다.
- 등록 요청에는 에셋 ID·테스트 순번을 받지 않는다.

**testCases[]**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `isSample` | boolean | Y | true 공개 예제, false 숨김. 둘 다 채점에 사용 |
| `argumentTexts` | object | Y | 매개변수 이름별 입력 JSON 텍스트. 키는 선언된 이름과 정확히 일치 |
| `expectedReturnText` | string | Y | 기대 반환값 JSON 텍스트 |
- 숫자는 정밀도 보존을 위해 JSON 텍스트로 보낸다. 예: `"a": "9007199254740993"`. 문자열 `abc`는 `"\"abc\""`.
- 같은 입력·기대값의 중복 테스트는 공개 여부와 무관하게 거절한다.
- TypeSpec 필드·지원 타입·검증 규칙은 하위 문서 「TypeSpec 공통 타입 명세」를 따른다.

**executionLimits**

| 필드 | 타입 | 단위 | 설명 |
| --- | --- | --- | --- |
| `timeMs` | int 또는 null | ms | CPU 시간 제한. 기본 2,000. C++ 정답 최대 실행 시간 × 3 이상, 최소 1,000 (B-5) |
| `memoryKb` | int 또는 null | KB | 메모리 제한. 기본 262,144 |
| `wallTimeMs` | int 또는 null | ms | 생략 시 `timeMs + 5,000` |
| `outputLimitKb` | int 또는 null | KB | 출력량 제한. 초과는 `RE`(출력 초과) |
- 언어별 보정은 두지 않는다. 최종 값은 회차에 저장하고 이후 공통 설정 변경은 기존 회차에 반영하지 않는다.

#### 2.3.1 문제 등록

- **Method / URI:** `POST /api/v1/admin/problems`
- **인증:** `ADMIN`
- **Idempotency:** 있음 (`Idempotency-Key` 필수)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_body_images`, `problem_images`, `problem_revision_tests`, `problem_test_assets`, `problem_revision_tags`, `audit_logs`
- **설명:** 완성된 본문과 전체 테스트로 문제와 첫 회차를 등록한다. 최초 상태는 `PRIVATE`다. 작성 단계 검증(정답 코드 AC 포함)은 등록 전 화면 절차로 수행한다.
- **요청 바디:**
    
    ```json
      {
        "scope": "GENERAL",
        "title": "두 수의 합",
        "description": "## 문제\n두 정수 a와 b의 합을 반환하세요.\n\n![덧셈 예시](image:701)",
        "constraints": "- 입력 범위: -1000 <= a, b <= 1000",
        "examples": "| a | b | 반환값 |\n|---|---|---|\n| 1 | 2 | 3 |",
        "difficulty": 1,
        "tagIds": ["1"],
        "functionSpec": {
          "name": "solution",
          "parameters": [ { 
    		      "name": "a", 
    		      "type": { "kind": "INT32" } 
    	      }, 
    	      { 
    		      "name": "b", 
    		      "type": { "kind": "INT32" } 
    	      } 
          ],
          "returnType": { "kind": "INT32" }
        },
        "executionLimits": { 
    	    "timeMs": 2000, 
    	    "memoryKb": null 
        },
        "testCases": [
          { 
    	      "isSample": true, 
    	      "argumentTexts": { 
    		      "a": "1", 
    		      "b": "2" 
    	      }, 
    	      "expectedReturnText": "3" 
          },
          { 
    	      "isSample": false, 
    	      "argumentTexts": { 
    		      "a": "-5", 
    		      "b": "7" 
    	      }, 
    	      "expectedReturnText": "2" 
          }
        ]
      }
    ```
    
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "문제가 등록되었습니다.", 
    	  "data": { 
    		  "problemId": "301", 
    		  "problemRevisionId": "1001", 
    		  "revisionNumber": 1, 
    		  "rowVersion": 1 
    	  } 
      }
    ```
    
- **에러:**
    - `COMMON_INVALID_REQUEST`(400), `PROBLEM_TEST_CASE_INVALID`(400), `PROBLEM_TAG_NOT_FOUND`(404), `PROBLEM_IMAGE_INVALID`(400) / 본문 이미지 10개 초과·미검증 이미지 참조, `IDEMPOTENCY_KEY_CONFLICT`(422)
- **동시성/멱등 보장:** S3 업로드 성공 후 DB 트랜잭션에 회차·파일 연결을 저장한다. DB 실패 시 업로드 파일은 `PENDING`으로 남아 정리 배치 대상이 된다.

#### 2.3.2 관리자 문제 목록 조회

- **Method / URI:** `GET /api/v1/admin/problems`
- **인증:** `ADMIN`
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tags`
- **설명:** 작성자와 무관하게 전체 문제의 회차 목록을 조회한다. 기본은 보관되지 않은 문제의 최신 회차만, `latestOnly=false`면 과거 회차도 포함한다. 한 행은 하나의 `problemRevisionId`다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `keyword` | string | N | — | 해당 회차 제목 검색 |
    | `difficulty` | int | N | — | 1~3 |
    | `tagIds` | string[] | N | — | OR 조건 |
    | `scope` | string | N | — | `GENERAL` / `EXAM_ONLY` |
    | `visibility` | string | N | `PRIVATE`·`PUBLIC` | 생략 시 `ARCHIVED` 제외 |
    | `latestOnly` | boolean | N | true | true면 최신 회차를 먼저 고른 뒤 필터 적용 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    | `sort` | string[] | N | `problemId,asc` 
    → `createdAt,desc` | 여러 개 전달 시 순서대로 적용 |
    
    허용 정렬 필드: `problemId`, `createdAt`(회차 등록일)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { 
    	      "problemId": "301", 
    	      "problemRevisionId": "1001", 
    	      "revisionNumber": 1, 
    	      "title": "두 수의 합", 
    	      "authorId": "11", 
    	      "scope": "GENERAL", 
    	      "visibility": "PUBLIC", 
    	      "rowVersion": 7 
    	      } 
    	    ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    
    - `rowVersion`은 문제 단위 값이라 같은 문제의 행에서 공유한다.

#### 2.3.3 관리자 문제 상세 조회

- **Method / URI:** `GET /api/v1/admin/problems/{problemId}`
- **인증:** `ADMIN` (작성 관리자)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_revision_tests`, `problem_test_assets`, `problem_revision_tags`, `tags`
- **설명:** 문제 기본 정보·공개 상태·`rowVersion`과 최신 회차의 전체 내용(숨김 테스트 포함)을 반환한다. 수정 화면은 이 API 한 번으로 채운다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "problemId": "301", 
          "authorId": "11", 
          "scope": "GENERAL", 
          "visibility": "PUBLIC", 
          "rowVersion": 7,
          "problemRevisionId": "1002", 
          "revisionNumber": 2, 
          "publishedAt": "2026-09-28T10:20:00+09:00",
          "title": "두 수의 합", 
          "description": "...", 
          "constraints": "...", 
          "examples": "...",
          "difficulty": 1, 
          "tagIds": ["1"],
          "functionSpec": { 
    	      "name": "solution", 
    	      "parameters": [ { 
    		      "name": "a", 
    		      "type": { "kind": "INT32" } 
    		      } 
    	      ], 
    	      "returnType": { "kind": "INT32" } 
          },
          "executionLimits": { 
    	      "timeMs": 2000, 
    	      "memoryKb": 262144, 
    	      "wallTimeMs": 7000, 
    	      "outputLimitKb": 64 
          },
          "testCases": [ { 
    	      "testAssetId": "501", 
    	      "isSample": true, 
    	      "argumentTexts": { "a": "1", "b": "2" }, 
    	      "expectedReturnText": "3" 
    	      } 
          ]
        }
      }
    ```
    
    - `testAssetId`는 조회 응답 전용이며 등록·수정 요청에는 포함하지 않는다.
- **에러:**
    - `PROBLEM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403) / 작성 관리자가 아님

#### 2.3.4 새 문제 수정 회차 등록

- **Method / URI:** `POST /api/v1/admin/problems/{problemId}/revisions`
- **인증:** `ADMIN` (작성 관리자)
- **Idempotency:** 있음 (`Idempotency-Key` 필수)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_body_images`, `problem_images`, `problem_revision_tests`, `problem_test_assets`, `problem_revision_tags`, `outbox_events`
- **설명:** 전체 본문·테스트로 새 불변 회차를 등록하고 현재 회차를 교체한다. 기존 회차·제출·시험은 그대로 두고 `scope`·공개 상태는 바꾸지 않는다.
- **요청 바디:** 문제 등록(2.3.1)과 같다. 단 `scope` 대신 `rowVersion`을 보낸다.
    
    ```json
      { 
    	  "rowVersion": 6, 
    	  "title": "두 수의 합", 
    	  "description": "...", 
    	  "constraints": "...", 
    	  "examples": "...", 
    	  "difficulty": 1, 
    	  "tagIds": ["1"], 
    	  "functionSpec": { "...": "..." }, 
    	  "executionLimits": null, 
    	  "testCases": [ { 
    		  "isSample": true, 
    		  "argumentTexts": { "a": "1", "b": "2" }, 
    		  "expectedReturnText": "3" 
    		  } 
    	  ] 
      }
    ```
    
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "새 회차가 등록되었습니다.", 
    	  "data": { 
    		  "problemRevisionId": "1002", 
    		  "revisionNumber": 2, 
    		  "rowVersion": 7 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `PROBLEM_VERSION_CONFLICT`(409), `PROBLEM_TEST_CASE_INVALID`(400), `IDEMPOTENCY_KEY_CONFLICT`(422)
- **동시성/멱등 보장:** `problems` 행을 `rowVersion` 조건으로 갱신한 뒤 다음 `revision_number`를 계산한다(`uk_problem_revisions_problem_revision_number`).
- **발행:**
    - `ProblemStateChanged` — 공개 문제의 새 회차 등록 시 (`revisionNumber` 갱신)

#### 2.3.5 문제 수정 회차 내용 조회

- **Method / URI:** `GET /api/v1/admin/problems/{problemId}/revisions/{revisionId}`
- **인증:** `ADMIN` (작성 관리자)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_revision_tests`, `problem_test_assets`, `problem_revision_tags`, `tags`
- **설명:** 지정한 과거·현재 회차의 전체 내용을 조회한다. 응답은 관리자 문제 상세 조회(2.3.3)에서 `scope`·`visibility`·`rowVersion`을 뺀 구조다.
- **에러:**
    - `PROBLEM_NOT_FOUND`(404), `PROBLEM_REVISION_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403)

#### 2.3.6 문제 수정 기록 목록 조회

- **Method / URI:** `GET /api/v1/admin/problems/{problemId}/revisions`
- **인증:** `ADMIN` (작성 관리자)
- **대응 테이블:** `problems`, `problem_revisions`
- **설명:** 회차 메타 요약만 반환한다. S3 본문·테스트는 읽지 않는다. 보관된 문제도 조회할 수 있다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `revisionNumber` (기본 `revisionNumber,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            { "problemRevisionId": "1002", "revisionNumber": 2, "title": "두 수의 합", "publishedAt": "2026-09-28T10:20:00+09:00" },
            { "problemRevisionId": "1001", "revisionNumber": 1, "title": "두 수의 합", "publishedAt": "2026-09-27T14:00:00+09:00" }
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 2, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    

#### 2.3.7 문제 공개

- **Method / URI:** `POST /api/v1/admin/problems/{problemId}/publication`
- **인증:** `ADMIN` (작성 관리자)
- **Idempotency:** 있음
- **대응 테이블:** `problems`, `problem_revisions`, `audit_logs`, `outbox_events`
- **설명:** 회차가 있는 `GENERAL` 문제를 `PUBLIC`으로 전환한다. `EXAM_ONLY` 문제는 대회 설정으로 공개된다.
- **요청 바디:**
    
    ```json
      { "rowVersion": 8 }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "문제가 공개되었습니다.", 
    	  "data": { 
    		  "visibility": "PUBLIC", 
    		  "rowVersion": 9 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_VERSION_CONFLICT`(409), `PROBLEM_INVALID_STATE_TRANSITION`(409) / `EXAM_ONLY`·이미 `PUBLIC`·`ARCHIVED`(재공개 허용 여부 확정 필요)
- **발행:**
    - `ProblemStateChanged` — `visibility = PUBLIC`

#### 2.3.8 문제 비공개 전환

- **Method / URI:** `POST /api/v1/admin/problems/{problemId}/unpublication`
- **인증:** `ADMIN` (작성 관리자)
- **Idempotency:** 있음
- **대응 테이블:** `problems`, `audit_logs`, `outbox_events`
- **설명:** `PUBLIC → PRIVATE`. 일반 목록 노출과 새 일반 풀이 접수를 중단한다. 회차·제출·이미 시험에 고정된 회차는 유지한다.
- **요청 바디:**
    
    ```json
      { "rowVersion": 9 }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "문제가 비공개로 전환되었습니다.", 
    	  "data": { 
    		  "visibility": "PRIVATE", 
    		  "rowVersion": 10 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_VERSION_CONFLICT`(409), `PROBLEM_INVALID_STATE_TRANSITION`(409)
    - `PROBLEM_IN_ACTIVE_EXAM`(409) / 진행 중 시험·대회에 포함 (예정 시험 처리는 미결정 B-9)
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:**
    - `GET /internal/v1/exams/problem-usage?problemId=` (contest) — 진행 중 시험·대회 포함 여부. 실패 시 fail-closed
- **발행:**
    - `ProblemStateChanged` — `visibility = PRIVATE` (study 문제집 `EXCLUDED` 처리)

#### 2.3.9 문제 보관

- **Method / URI:** `POST /api/v1/admin/problems/{problemId}/archive`
- **인증:** `ADMIN` (작성 관리자)
- **Idempotency:** 있음
- **대응 테이블:** `problems`, `audit_logs`, `outbox_events`
- **설명:** `ARCHIVED`(소프트 딜리트)로 전환해 새 풀이·시험 선택을 막는다. 기존에 고정한 회차는 유지한다. 해제 여부는 정책 검토 대상이다.
- **요청 바디:**
    
    ```json
      { "rowVersion": 9, "reason": "신규 풀이 제공 종료" }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "문제가 보관되었습니다.", 
    	  "data": { 
    		  "visibility": "ARCHIVED", 
    		  "rowVersion": 10 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_VERSION_CONFLICT`(409), `PROBLEM_INVALID_STATE_TRANSITION`(409), `PROBLEM_IN_ACTIVE_EXAM`(409)
- **발행:**
    - `ProblemStateChanged` — `visibility = ARCHIVED`

### 2.4 관리자 API — 본문 이미지

#### 2.4.1 본문 이미지 업로드

- **Method / URI:** `POST /api/v1/admin/problems/images`
- **인증:** `ADMIN`
- **Idempotency:** 있음
- **대응 테이블:** `problem_images`
- **설명:** 이미지 한 개를 백엔드로 업로드한다(`multipart/form-data`, 필드 `file`). 검증·S3 저장 후 `imageId`를 반환한다. 문제 등록 전에도 올릴 수 있으며 첫 본문 연결 시 소속 문제가 정해진다.
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": null, 
    	  "data": { "imageId": "701" } 
      }
    ```
    
    - 본문에는 S3 주소 대신 `![설명](image:701)`로 넣는다.
    - 허용: 정적 PNG/JPEG/WebP, 개당 5MiB, 2,000만 픽셀 이하. 본문당 서로 다른 이미지 10개 이하(등록 시 검사). SVG/GIF 제외
- **에러:**
    - `PROBLEM_IMAGE_INVALID`(400), `PROBLEM_IMAGE_TOO_LARGE`(413)
- **동시성/멱등 보장:** 추적 행(`PENDING`)을 먼저 만든 뒤 검증·저장한다. 본문에 연결되지 않은 이미지는 대기 시간(미결정) 후 정리한다.

#### 2.4.2 관리자 이미지 조회

- **Method / URI:** `GET /api/v1/admin/problems/images/{imageId}/content`
- **인증:** `ADMIN` (업로드 관리자)
- **대응 테이블:** `problem_images`
- **설명:** 등록 전 미리보기·편집용. 백엔드가 S3에서 읽어 바이너리로 반환한다.
- **응답 (200):** 이미지 바이너리 (`Cache-Control: no-store`, `X-Content-Type-Options: nosniff`)
- **에러:**
    - `PROBLEM_NOT_FOUND`(404) / 없음·본인 업로드 아님

### 2.5 외부·관리자 API — 태그

#### 2.5.1 태그 목록 조회

- **Method / URI:** `GET /api/v1/problems/tags`
- **인증:** 로그인 필요
- **대응 테이블:** `tags`
- **설명:** 태그 목록(프로그래머스 고득점 Kit 기준)을 반환한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `keyword` | string | N | — | 태그명 검색 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 100 |  |
    
    허용 정렬 필드: `displayOrder`, `name` (기본 `displayOrder,asc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { "tagId": "1", "name": "해시" }, 
    	      { "tagId": "2", "name": "스택/큐" } 
          ],
          "page": 0, 
          "size": 100, 
          "totalElements": 10, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    

#### 2.5.2 태그 생성

- **Method / URI:** `POST /api/v1/admin/problems/tags`
- **인증:** `ADMIN`
- **Idempotency:** 있음
- **대응 테이블:** `tags`
- **요청 바디:**
    
    ```json
      { "name": "해시", "displayOrder": 1 }
    ```
    
- **응답 (201):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "태그가 생성되었습니다.", 
    	  "data": { 
    		  "tagId": "1", 
    		  "name": "해시" 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_TAG_DUPLICATED`(409)

#### 2.5.3 태그 수정

- **Method / URI:** `PATCH /api/v1/admin/problems/tags/{tagId}`
- **인증:** `ADMIN`
- **대응 테이블:** `tags`
- **설명:** 이름·표시 순서를 바꾼다. 새 회차를 만들지 않으며 연결된 현재·과거 회차에 바뀐 이름이 표시된다.
- **요청 바디:**
    
    ```json
      { "name": "그리디" }
    ```
    
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": "태그가 수정되었습니다.", 
    	  "data": { 
    		  "tagId": "2", 
    		  "name": "그리디" 
    	  } 
      }
    ```
    
- **에러:**
    - `PROBLEM_TAG_NOT_FOUND`(404), `PROBLEM_TAG_DUPLICATED`(409)

#### 2.5.4 태그 삭제

- **Method / URI:** `DELETE /api/v1/admin/problems/tags/{tagId}`
- **인증:** `ADMIN`
- **대응 테이블:** `tags`, `problem_revision_tags`
- **설명:** 어떤 회차에서도 쓰지 않는 태그만 삭제한다. 연결을 자동 해제하지 않는다.
- **응답 (204)**
- **에러:**
    - `PROBLEM_TAG_NOT_FOUND`(404), `PROBLEM_TAG_IN_USE`(409)

### 2.6 내부 API

#### 2.6.1 채점 대상 사전 검증

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/judging/validation`
- **인증:** 서비스 계정 (judge)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tests`, `problem_test_assets`
- **설명:** 지정 회차의 존재·문제 일치·함수 명세·실행 제한·테스트 연결·파일 준비 상태를 확인한다. 최신 회차 여부는 검사하지 않는다. 사용 기록을 만들지 않으므로 GET으로 둔다(기존 목록의 POST 표기 정정).
- **응답 (200):**
    
    ```json
      { 
    	  "problemId": "301", 
    	  "problemRevisionId": "1001", 
    	  "scope": "GENERAL", 
    	  "visibility": "PUBLIC", 
    	  "difficulty": 1, 
    	  "tagIds": ["1"], 
    	  "totalCount": 12, 
    	  "testAssetIds": ["501", "502"] 
      }
    ```
    
    - `PRACTICE`는 judge가 `scope = GENERAL`·`visibility = PUBLIC`을 확인한다. 시험·대회는 contest가 고정 회차 권한을 확인하므로 이 조건을 적용하지 않는다.
    - `difficulty`·`tagIds`: 레벨 접근 제한 판단과 `SubmissionJudged` 스냅샷용
- **실패 시 처리:** judge는 제한 재시도 후 fail-closed. 최신 회차로 자동 치환 금지

#### 2.6.2 채점 기준 데이터 조회

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/judging/data`
- **인증:** 서비스 계정 (judge)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tests`, `problem_test_assets`
- **설명:** 고정 회차의 함수 명세·실행 제한·테스트 파일 참조를 반환한다. 제출 가능 여부는 다시 검사하지 않는다. 테스트 원문은 judge 실행기가 제한된 S3 읽기 권한으로 가져온다.
- **응답 (200):**
    
    ```json
      {
        "problemId": "301", 
        "problemRevisionId": "1001",
        "functionSpec": { 
    	    "name": "solution", 
    	    "parameters": [ { 
    		    "name": "a", 
    		    "type": { "kind": "INT32" } 
    		    } 
    	    ], 
    	    "returnType": { "kind": "INT32" } 
        },
        "executionLimits": { 
    	    "timeMs": 2000, 
    	    "memoryKb": 262144, 
    	    "wallTimeMs": 7000, 
    	    "outputLimitKb": 64 
        },
        "cases": [ { 
    	    "testAssetId": "501", 
    	    "testOrder": 1, 
    	    "isSample": true, 
    	    "asset": { 
    		    "objectKey": "test-cases/501.json", 
    		    "objectVersion": "v-501", "sha256": "c6b8..." 
    		    } 
    	    } 
        ]
      }
    ```
    

#### 2.6.3 고정 회차 본문 조회

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content`
- **인증:** 서비스 계정 (contest, member) + 호출 맥락 헤더 `X-Access-Context: EXAM:{examId}` / `CONTEST:{contestId}` / `DIAGNOSIS:{diagnosisId}`
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_revision_tests`, `problem_test_assets`
- **설명:** 고정 회차의 본문·함수 명세·실행 제한·공개 예제를 반환한다. 숨김 테스트는 읽지 않는다. 참가·공개 권한은 호출 서비스가 확인한 뒤 호출한다.
- **응답 (200):** 문제 상세 조회(2.1.2)와 같은 형식
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "problemId": "301",
          "problemRevisionId": "1001",
          "revisionNumber": 1,
          "title": "두 수의 합",
          "difficulty": 1,
          "tags": [ 
    	      { 
    		      "tagId": "1", 
    		      "name": "구현" 
    	      } 
          ],
          "publishedAt": "2026-09-27T10:00:00+09:00",
          "solvedStatus": "UNSOLVED",
          "description": "## 문제\n두 정수 a와 b의 합을 반환하세요.\n\n![덧셈 예시](image:701)",
          "constraints": "- 입력 범위: -1000 <= a, b <= 1000",
          "examples": "| a | b | 반환값 |\n|---|---|---|\n| 1 | 2 | 3 |",
          "functionSpec": {
            "name": "solution",
            "parameters": [ 
    	        { 
    		        "name": "a", 
    		        "type": { "kind": "INT32" } 
    	        }, 
    	        { 
    		        "name": "b", 
    		        "type": { "kind": "INT32" } 
    	        } 
            ],
            "returnType": { "kind": "INT32" }
          },
          "executionLimits": { 
    	      "timeMs": 2000, 
    	      "memoryKb": 262144, 
    	      "wallTimeMs": 7000, 
    	      "outputLimitKb": 64 
          },
          "starterCodes": [ 
    	      { 
    		      "language": "python3", 
    		      "sourceCode": "def solution(a, b):\n    pass\n" 
    	      } 
          ],
          "samples": [ 
    	      { 
    		      "testAssetId": "501", 
    		      "argumentTexts": { "a": "1", "b": "2" }, 
    		      "expectedReturnText": "3" 
    	      } 
          ]
        }
      }
    ```
    

#### 2.6.4 고정 회차 본문 이미지 조회

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}`
- **인증:** 서비스 계정 (contest, member) + `X-Access-Context`
- **대응 테이블:** `problem_body_images`, `problem_images`
- **설명:** 고정 회차 본문에 연결된 이미지 바이너리. 브라우저에는 contest·member의 권한 검사 경로로 전달한다.

#### 2.6.5 문제 선택용 요약 조회

- **Method / URI:** `GET /internal/v1/problems/summaries`
- **인증:** 서비스 계정 (study, contest)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tags`, `tags`
- **설명:** 문제집·시험 생성 시 회차 고정·표시용 요약을 반환한다(B-2). 요청 ID 중 사용 불가 문제는 `isAvailable: false`로 표시한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `ids` | string[] | Y | — | 문제 ID 최대 50개 |
    | `purpose` | string | Y | — | `ASSIGNMENT` / `EXAM`(`GENERAL`·`PUBLIC`만 가용) / `CONTEST`(`EXAM_ONLY` 허용) |
- **응답 (200):**
    
    ```json
      {
        "items": [
          { 
    	      "problemId": "101", 
    	      "problemRevisionId": "201", 
    	      "revisionNumber": 3, 
    	      "title": "최단 경로", 
    	      "difficulty": 3, 
    	      "tags": [ { 
    		      "tagId": "7", 
    		      "name": "그래프" 
    		      } 
    	      ], 
    	      "scope": "GENERAL", 
    	      "visibility": "PUBLIC", 
    	      "isAvailable": true 
          }
        ]
      }
    ```
    

#### 2.6.6 힌트 문맥 조회

- **Method / URI:** `GET /internal/v1/problems/{problemId}/hint-context`
- **인증:** 서비스 계정 (study AI 모듈)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_body_assets`, `problem_revision_tags`
- **설명:** 힌트 입력(제목·본문·태그)과 현재 공개 회차, 힌트 가능 여부를 반환한다. 대회 전용 문제는 대회 종료 전 `isHintAvailable: false`.
- **응답 (200):**
    
    ```json
      { 
    	  "problemId": "101", 
    	  "problemRevisionId": "201", 
    	  "title": "최단 경로", 
    	  "description": "...", 
    	  "tags": ["그래프"], 
    	  "isHintAvailable": true 
      }
    ```
    

#### 2.6.7 진단 출제 후보 조회

- **Method / URI:** `GET /internal/v1/problems/diagnosis-candidates`
- **인증:** 서비스 계정 (member)
- **대응 테이블:** `problems`, `problem_revisions`, `problem_revision_tags`
- **설명:** 태그의 `GENERAL`·`PUBLIC` 문제 중 난이도 1·2·3에서 각각 1개를 무작위로 골라 현재 회차와 함께 반환한다. 난이도별 후보가 없으면 해당 항목을 `null`로 반환한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `tagId` | string | Y | — | 태그 ID |
- **응답 (200):**
    
    ```json
      { 
    	  "tagId": "3", 
    	  "problems": [ 
    		  { 
    			  "difficulty": 1, 
    			  "problemId": "301", 
    			  "problemRevisionId": "1001", 
    			  "title": "두 수의 합" 
    		  }, 
    		  { 
    			  "difficulty": 2, 
    			  "problemId": "315", 
    			  "problemRevisionId": 
    			  "1040", "title": "..." 
    		  }, 
    		  { 
    			  "difficulty": 3, 
    			  "problemId": "330", 
    			  "problemRevisionId": "1092", 
    			  "title": "..." 
    		  } 
    	  ] 
      }
    ```
    

---

## 3. 제출·채점 (judge)

> 작성: 담당 B · 서비스: `judge-service`
> 

### 3.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/submissions/**`, `/api/v1/me/submissions`, `/api/v1/runs`, `/api/v1/judge/languages`, `/api/v1/admin/judge-jobs/**`, `/api/v1/admin/submissions/**` |
| 내부 경로 | `/internal/v1/submissions/**` |
| 소유 테이블 | 스키마 `judge` (`submissions`, `judge_jobs`, `judge_job_runs`, `judge_test_results`) |
| 문맥 | `PRACTICE`(외부 API), `EXAM`·`CONTEST`(SQS `exam-submission-queue`), `DIAGNOSIS`(member 경유, 경로 확정 필요) |
| 지원 언어 | `java`(Java 17), `python3`(Python 3), `cpp`(C++17). 런타임 버전·Judge0 `language_id`는 배포 환경에서 확정 |
| 채점 | 202는 접수 완료이며 채점 완료가 아니다. 작업 원천은 `judge_jobs`, SKIP LOCKED 선점·임대, 실행 시도 상한 3회. |
| 회차 용어 | 채점 회차 `judgeAttempt`(재채점마다 +1), 실행 시도 `tryCount`(회차 안 재시도). 기존 초안의 `attemptId`·`attemptNo`는 `judgeAttempt`로 통일 |
| 재채점 | `PRACTICE`는 재채점하지 않는다. 채점 서버 장애로 실패한 `EXAM` 제출만 대상 (T2-11) |
| 결과 공개 | 숨김 테스트의 입력·출력은 어떤 API로도 반환하지 않는다. 시험·대회 판정 공개 시점은 contest 정책을 따른다 |
| 이벤트 발행 | `SubmissionJudged`(PRACTICE), `ExamSubmissionJudged`, `ContestSubmissionJudged`, `SubmissionFailed` |

### 3.1 외부 API — 언어·제출·실행

#### 3.1.1 지원 언어 조회

- **Method / URI:** `GET /api/v1/judge/languages`
- **인증:** 로그인 필요
- **대응 테이블:** 없음 (애플리케이션 설정)
- **설명:** 지원 언어와 활성화 여부를 반환한다. 배포 환경에서 검증하기 전 런타임은 `enabled: false`로 둔다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            { "language": "java", "displayName": "Java 17", "runtimeVersion": "배포 환경 확인 전", "enabled": false },
            { "language": "python3", "displayName": "Python 3", "runtimeVersion": "배포 환경 확인 전", "enabled": false },
            { "language": "cpp", "displayName": "C++17", "runtimeVersion": "배포 환경 확인 전", "enabled": false }
          ]
        }
      }
    ```
    

#### 3.1.2 일반 풀이 제출

- **Method / URI:** `POST /api/v1/submissions`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **Idempotency:** 있음 (`Idempotency-Key` 필수, 24시간 보관)
- **Rate Limit:** 사용자당 분당 10회, 동일 문제 5초 간격
- **대응 테이블:** `submissions`, `judge_jobs`, `judge_test_results`
- **설명:** 사용자 코드를 제출하고 비동기 채점을 접수한다. 최신 회차와 달라도 요청한 회차로 제출할 수 있다.
- **요청 바디:**
    
    ```json
      { 
    	  "problemId": "301", 
    	  "problemRevisionId": "1001", 
    	  "language": "python3", 
    	  "sourceCode": "def solution(a, b):\n    return a + b\n" 
      }
    ```
    
    - 네 필드 모두 필수. `sourceCode`는 UTF-8 64KB 이하, 실행용 래퍼 코드는 포함하지 않는다.
    - `userId`·`examId`·제한값·`expectedOutput`·`callbackUrl`은 받지 않는다.
- **응답 (202):**
    
    ```json
      {
        "success": true,
        "message": "제출이 접수되었습니다.",
        "data": { 
    	    "submissionId": "2001", 
    	    "judgeAttempt": 1, 
    	    "status": "QUEUED", 
    	    "submittedAt": "2026-09-28T10:00:00+09:00", 
    	    "statusUrl": "/api/v1/submissions/2001" 
        }
      }
    ```
    
- **에러:**
    - `UNSUPPORTED_LANGUAGE`(400), `CODE_TOO_LARGE`(413), `SUBMISSION_RATE_LIMITED`(429)
    - `PROBLEM_NOT_AVAILABLE`(404) / `PRIVATE`·`ARCHIVED`·`EXAM_ONLY`
    - `LEVEL_PROBLEM_LOCKED`(403) / 태그 레벨보다 높은 난이도
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503) / 사전 검증·레벨 조회 실패 (같은 키·본문으로 재시도 안내)
    - `IDEMPOTENCY_KEY_CONFLICT`(422), `MEMBER_SUSPENDED`(403)
- **동시성/멱등 보장:**
    - 같은 키의 기존 접수가 있으면 검증 없이 원래 응답을 반환한다(`uk_submissions_member_idempotency_key` + `request_hash`).
    - 검증 통과 후 `submissions`(`QUEUED`)·`judge_jobs`(`PENDING`)·테스트별 결과 자리를 한 트랜잭션에 저장한다. 기존 초안의 `PREPARING` 선저장 단계는 정책 v1.0(검증 → 단일 트랜잭션)에 맞춰 뺐다.
    - 일반 풀이 채점 요청은 SQS를 거치지 않는다.
- **외부 의존:**
    - `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/judging/validation` (problem) — 회차 검증. 실패 시 fail-closed
    - `GET /internal/v1/members/{memberId}/tag-levels` (member) — 레벨 접근 제한. 실패 시 fail-closed
- **프론트 계약:**
    - 202 후 SSE(`/api/v1/notifications/subscribe`)의 `SUBMISSION_JUDGED` 알림을 받으면 제출 상태·결과 조회(3.1.3)으로 결과를 조회한다. SSE 연결이 없으면 3.1.3을 2초 → 5초 간격으로 조회한다.
    - 응답이 유실되면 같은 키·본문으로 재요청한다. 새 코드에는 새 키를 쓴다.
    - 작성 중 코드는 브라우저에 사용자·문제·언어별로 자동 저장한다(서버 Draft API 없음).

#### 3.1.3 제출 상태·결과 조회

- **Method / URI:** `GET /api/v1/submissions/{submissionId}`
- **인증:** 로그인 필요 (본인 `PRACTICE` 제출)
- **대응 테이블:** `submissions`, `judge_jobs`
- **설명:** 제출 상태와 최신 유효 판정을 반환한다. 재채점 진행 중에도 직전 회차 결과를 `effectiveResult`로 유지한다. 시스템 실패는 `status = FAILED`로 전달하며 WA로 바꾸지 않는다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "submissionId": "2001", 
          "problemId": "301", 
          "problemRevisionId": "1001", 
          "language": "python3",
          "context": "PRACTICE", 
          "status": "COMPLETED", 
          "judgeAttempt": 1, 
          "submittedAt": "2026-09-28T10:00:00+09:00",
          "effectiveResult": { 
    	      "judgeAttempt": 1, 
    	      "verdict": "AC", 
    	      "passedCount": 12, 
    	      "totalCount": 12, 
    	      "maxTimeMs": 12, 
    	      "maxMemoryKb": 8192, 
    	      "judgedAt": "2026-09-28T10:00:02+09:00" 
          },
          "compileMessage": null,
          "failureCode": null
        }
      }
    ```
    
    - `status`: `QUEUED` / `JUDGING` / `RETRY_WAITING` / `COMPLETED` / `FAILED`. `RETRY_WAITING`은 화면에 "채점 중"으로 표시
    - `compileMessage`: `CE`일 때만, 본인에게만 제공
    - `failureCode`: `FAILED`일 때 (예: `ENGINE_UNAVAILABLE`)
- **에러:**
    - `SUBMISSION_NOT_FOUND`(404)

#### 3.1.4 내 제출 목록 조회

- **Method / URI:** `GET /api/v1/me/submissions`
- **인증:** 로그인 필요
- **대응 테이블:** `submissions`
- **설명:** 모든 문맥의 본인 제출을 문맥과 함께 보여준다. 시험·대회 제출의 판정은 해당 문맥 공개 정책 전까지 `null`로 가린다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `problemId` | string | N | — | 문제 필터 |
    | `context` | string | N | — | `PRACTICE` / `EXAM` / `CONTEST` / `DIAGNOSIS` |
    | `verdict` | string | N | — | 판정 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `submittedAt` (기본 `submittedAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { 
    		      "submissionId": "2001", 
    		      "problemId": "301", 
    		      "language": "python3", 
    		      "context": "PRACTICE", 
    		      "status": "COMPLETED", 
    		      "verdict": "AC", 
    		      "maxTimeMs": 12, 
    		      "maxMemoryKb": 8192, 
    		      "submittedAt": "2026-09-28T10:00:00+09:00" 
    	      } 
          ],
          "page": 0, 
          "size": 20, 
          "totalElements": 1, 
          "totalPages": 1, 
          "hasNext": false
        }
      }
    ```
    
    - 문제 제목은 저장하지 않는다. 화면은 문제 서비스 조회로 채운다.
    - 시험·대회 판정 공개 여부를 judge가 모르므로, 해당 제출의 `verdict`는 항상 `null`로 두고 contest 화면에서 확인하게 한다(**공개 정책 연동 방식 확정 필요**).

#### 3.1.5 제출 코드 조회

- **Method / URI:** `GET /api/v1/submissions/{submissionId}/source`
- **인증:** 로그인 필요 (본인 `PRACTICE` 제출)
- **대응 테이블:** `submissions`
- **응답 (200):**
    
    ```json
      { 
    	  "success": true, 
    	  "message": null, 
    	  "data": { 
    		  "language": "python3", 
    		  "sourceCode": "def solution(a, b):\n    return a + b\n" 
    	  } 
      }
    ```
    
    - 응답 헤더 `Cache-Control: no-store`
- **에러:**
    - `SUBMISSION_NOT_FOUND`(404)

#### 3.1.6 채점 회차 목록 조회

- **Method / URI:** `GET /api/v1/submissions/{submissionId}/judge-attempts`
- **인증:** 로그인 필요 (본인 `PRACTICE` 제출)
- **대응 테이블:** `judge_jobs`
- **설명:** 제출의 채점 회차 이력을 반환한다. `PRACTICE`는 재채점이 없어 보통 1건이다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ 
    	      { 
    		      "judgeAttempt": 1, 
    		      "trigger": "INITIAL", 
    		      "status": "SUCCEEDED", 
    		      "verdict": "AC", 
    		      "passedCount": 12, 
    		      "totalCount": 12, 
    		      "maxTimeMs": 12, 
    		      "maxMemoryKb": 8192, 
    		      "createdAt": "2026-09-28T10:00:00+09:00", 
    		      "finishedAt": "2026-09-28T10:00:02+09:00" 
    	      } 
          ]
        }
      }
    ```
    
    - `trigger`: `INITIAL` / `REJUDGE` / `REPROCESS`

#### 3.1.7 테스트별 결과 조회

- **Method / URI:** `GET /api/v1/submissions/{submissionId}/judge-attempts/{judgeAttempt}/test-results`
- **인증:** 로그인 필요 (본인 `PRACTICE` 제출)
- **대응 테이블:** `judge_test_results`
- **설명:** 회차의 테스트별 판정·성능을 반환한다. 공개 테스트는 입력·기대값·실제 출력을, 숨김 테스트는 번호와 통과 여부만 반환한다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            { 
    	        "testOrder": 1, 
    	        "isSample": true, 
    	        "verdict": "AC", 
    	        "timeMs": 12, 
    	        "memoryKb": 8192, 
    	        "argumentTexts": { "a": "1", "b": "2" }, 
    	        "expectedReturnText": "3", 
    	        "actualReturnText": "3" 
            },
            { 
    	        "testOrder": 2, 
    	        "isSample": false, 
    	        "verdict": "AC", 
    	        "timeMs": 10, 
    	        "memoryKb": 8100 
            }
          ]
        }
      }
    ```
    
    - token·S3 경로·체크섬·stderr는 반환하지 않는다.

#### 3.1.8 코드 실행 (Run)

- **Method / URI:** `POST /api/v1/runs`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **Rate Limit:** `run:{userId}` 키 기반 원자적 제한(한도 수치 확정 필요, 쿨다운 없음)
- **대응 테이블:** 없음 (결과 저장·이벤트 발행 없음)
- **설명:** 공개 테스트케이스 또는 사용자 직접 입력으로 코드를 실행한다. 제출 이력에 포함하지 않고 Judge0를 동기 호출한다(T2-14).
- **요청 바디:**
    
    ```json
      {
        "problemId": "301", 
        "problemRevisionId": "1001", 
        "language": "python3",
        "sourceCode": "def solution(a, b):\n    return a + b\n",
        "useSamples": true,
        "customInputs": [ 
    	    { 
    		    "argumentTexts": { "a": "100", "b": "200" } 
    	    } 
        ]
      }
    ```
    
    - `customInputs` 최대 5개, 함수 명세로 타입 검증
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "results": [
            { 
    	        "source": "SAMPLE", 
    	        "testAssetId": "501", 
    	        "verdict": "AC", 
    	        "actualReturnText": "3", 
    	        "expectedReturnText": "3", 
    	        "timeMs": 11, 
    	        "memoryKb": 8000, 
    	        "stdout": "" 
            },
            { 
    	        "source": "CUSTOM", 
    	        "index": 0, 
    	        "verdict": null, 
    	        "actualReturnText": "300", 
    	        "timeMs": 10, 
    	        "memoryKb": 8000, 
    	        "stdout": "" 
            }
          ],
          "compileMessage": null
        }
      }
    ```
    
    - `CUSTOM`은 기대값이 없어 `verdict`가 `null`(실행 오류면 `RE`·`TLE` 등)
    - `stdout`은 디버그 출력, 출력량 제한 적용
- **에러:**
    - `UNSUPPORTED_LANGUAGE`(400), `CODE_TOO_LARGE`(413), `RUN_RATE_LIMITED`(429), `PROBLEM_NOT_AVAILABLE`(404), `COMMON_DEPENDENCY_UNAVAILABLE`(503) / Judge0 장애

### 3.2 관리자 API — 진단·복구·재채점

#### 3.2.1 채점 작업 목록 조회

- **Method / URI:** `GET /api/v1/admin/judge-jobs`
- **인증:** `ADMIN`
- **대응 테이블:** `judge_jobs`, `submissions`
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | `PENDING` / `RUNNING` / `RETRY_WAITING` / `SUCCEEDED` / `FAILED` |
    | `context` | string | N | — | 제출 문맥 |
    | `contextId` | string | N | — | 시험·대회 ID |
    | `failureCode` | string | N | — | 실패 코드 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `createdAt`, `updatedAt` (기본 `updatedAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { "judgeJobId": "9101", "submissionId": "2002", "judgeAttempt": 1, "status": "FAILED", "tryCount": 3, "failureCode": "ENGINE_UNAVAILABLE", "memberId": "11", "context": "EXAM", "contextId": "401", "problemRevisionId": "1001", "deadlineAt": "2026-09-28T10:10:00+09:00", "createdAt": "2026-09-28T10:00:00+09:00", "updatedAt": "2026-09-28T10:01:00+09:00" } ],
          "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
        }
      }
    ```
    

#### 3.2.2 채점 작업 상세 조회

- **Method / URI:** `GET /api/v1/admin/judge-jobs/{judgeJobId}`
- **인증:** `ADMIN`
- **대응 테이블:** `judge_jobs`, `judge_job_runs`, `judge_test_results`, `submissions`
- **설명:** 작업과 실행 시도 이력·테스트별 token 상태를 반환한다. 문제 본문·테스트 원문은 포함하지 않는다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "judgeJobId": "9101", "submissionId": "2002", "judgeAttempt": 1, "status": "FAILED", "tryCount": 3,
          "leaseUntil": null, "nextRunAt": null, "deadlineAt": "2026-09-28T10:10:00+09:00", "lastError": "Judge0 연결 실패",
          "runs": [ { "tryCount": 1, "result": "SYSTEM_ERROR", "errorMessage": "connect timeout", "startedAt": "…", "finishedAt": "…" } ],
          "testResults": [ { "testOrder": 1, "tokenStatus": "UNKNOWN", "judge0Token": null, "verdict": null } ]
        }
      }
    ```
    

#### 3.2.3 시험 제출 재채점 요청

- **Method / URI:** `POST /api/v1/admin/submissions/{submissionId}/rejudges`
- **인증:** `ADMIN` (시험 운영자 경로는 contest 경유 확정 필요)
- **Idempotency:** 있음
- **대응 테이블:** `submissions`, `judge_jobs`, `judge_test_results`, `audit_logs`
- **설명:** 채점 서버 장애로 실패한 `EXAM` 제출을 같은 원본 회차·런타임으로 재채점한다. `judge_attempt`를 1 올리고 새 작업을 만든다. 이전 회차 결과는 이력으로 보존한다.
- **요청 바디:**
    
    ```json
      { "reason": "채점 서버 장애 복구 후 재채점" }
    ```
    
- **응답 (202):**
    
    ```json
      { "success": true, "message": "재채점을 접수했습니다.", "data": { "submissionId": "2002", "judgeAttempt": 2, "status": "QUEUED" } }
    ```
    
- **에러:**
    - `SUBMISSION_NOT_FOUND`(404)
    - `SUBMISSION_REJUDGE_NOT_ALLOWED`(409) / `PRACTICE`·정상 판정·결과 확정(`FINALIZED`) 후
    - `SUBMISSION_JUDGING_IN_PROGRESS`(409)
    - `SUBMISSION_RUNTIME_UNAVAILABLE`(409), `SUBMISSION_REJUDGE_INPUT_UNAVAILABLE`(409)
- **동시성/멱등 보장:** `(submission_id, judge_attempt)` 유일 제약. 최신 회차로 대체하지 않는다.
- **외부 의존:**
    - `GET /internal/v1/exams/{examId}/rejudge-eligibility` (contest) — 결과 확정 전 여부. 실패 시 fail-closed
- **발행:**
    - `ExamSubmissionJudged`(새 `judgeAttempt`) — 재채점 완료 시

#### 3.2.4 실패 제출 재처리

- **Method / URI:** `POST /api/v1/admin/submissions/{submissionId}/reprocesses`
- **인증:** `ADMIN`
- **Idempotency:** 있음
- **대응 테이블:** `submissions`, `judge_jobs`, `audit_logs`
- **설명:** `FAILED` 제출(DLQ 격리 포함)을 `QUEUED`로 되돌려 새 회차로 다시 채점한다. `PRACTICE`는 사용자에게 새 제출을 안내하는 것이 원칙이며, 이 API는 시험·대회·진단과 운영 판단이 필요한 건에 쓴다.
- **요청 바디:**
    
    ```json
      { "reason": "DLQ 원인 수정 후 재처리" }
    ```
    
- **응답 (202):**
    
    ```json
      { "success": true, "message": "재처리를 접수했습니다.", "data": { "submissionId": "2002", "judgeAttempt": 2, "status": "QUEUED" } }
    ```
    
- **에러:**
    - `SUBMISSION_NOT_FOUND`(404), `SUBMISSION_REJUDGE_NOT_ALLOWED`(409) / `FAILED`가 아님

#### 3.2.5 진행 중 채점 작업 복구

- **Method / URI:** `POST /api/v1/admin/judge-jobs/{judgeJobId}/recoveries`
- **인증:** `ADMIN`
- **Idempotency:** 있음
- **대응 테이블:** `judge_jobs`, `judge_job_runs`, `audit_logs`
- **설명:** 종결되지 않은 작업을 같은 회차 안에서 재개한다(임대 해제 → `RETRY_WAITING`). 새 회차를 만들지 않고, 종결 상태를 되살리거나 시도 상한·처리 기한을 우회하지 않는다.
- **요청 바디:**
    
    ```json
      { "reason": "실행기 중단 후 작업 복구" }
    ```
    
- **응답 (202):**
    
    ```json
      { "success": true, "message": "복구를 요청했습니다.", "data": { "judgeJobId": "9103", "status": "RETRY_WAITING" } }
    ```
    
- **에러:**
    - `SUBMISSION_NOT_FOUND`(404), `SUBMISSION_REJUDGE_NOT_ALLOWED`(409) / 이미 종결된 작업

### 3.3 내부 API

> 시험·대회 채점 요청은 SQS `exam-submission-queue`로만 받는다. 
메시지 계약은 부록 B를 따른다.
> 

#### 3.3.1 시험·대회 제출 결과 조회

- **Method / URI:** `GET /internal/v1/submissions/{submissionId}/result`
- **인증:** 서비스 계정 (contest)
- **대응 테이블:** `submissions`, `judge_jobs`
- **설명:** 이벤트 유실 복구·결과 확정 전 대사용. 점수·공개 판단은 contest가 한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `submissionId` | string | Y | — | Path |
    | `contextId` | string | Y | — | 시험·대회 ID (불일치 시 404) |
- **응답 (200):**
    
    ```json
      { "submissionId": "2002", "context": "EXAM", "contextId": "401", "sourceSubmissionId": "4001", "status": "COMPLETED", "judgeAttempt": 1, "verdict": "AC", "passedCount": 12, "totalCount": 12, "maxTimeMs": 12, "maxMemoryKb": 8192, "submittedAt": "2026-09-28T10:00:00+09:00" }
    ```
    

#### 3.3.2 제출 코드 조회 (내부)

- **Method / URI:** `GET /internal/v1/submissions/{submissionId}/source`
- **인증:** 서비스 계정 (contest, problem)
- **대응 테이블:** `submissions`
- **설명:** 호출 목적별로 조건을 확인한 뒤 코드를 반환한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `purpose` | string | Y | — | `EXAM_REVIEW`(contest, 시험 결과 화면) / `SOLUTION_SHARE`(problem, 풀이 공유) |
    | `memberId` | string | Y | — | 호출 서비스가 인증으로 확인한 요청자 |
    | `contextId` | string | N | — | `EXAM_REVIEW`일 때 시험 ID |
    - `SOLUTION_SHARE`: 제출 소유자 = `memberId`, `context = PRACTICE`, 최신 판정 `AC`일 때만 반환
    - `EXAM_REVIEW`: 제출 소유자 = `memberId`, `contextId` 일치. 공개 시점 판단은 contest 책임
- **응답 (200):**
    
    ```json
      { "submissionId": "91023", "problemId": "301", "problemRevisionId": "1001", "language": "python3", "sourceCode": "…", "verdict": "AC" }
    ```
    
- **실패 시 처리:** 조건 불일치는 404. 호출 서비스는 fail-closed

#### 3.3.3 GitHub 동기화용 정답 코드 조회

- **Method / URI:** `GET /internal/v1/submissions/{submissionId}/accepted/code`
- **인증:** 서비스 계정 (integration)
- **대응 테이블:** `submissions`
- **설명:** `PRACTICE` AC 제출의 코드를 반환한다. 시험·대회·진단 제출은 거절한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `memberId` | string | Y | — | 연동 회원 (제출 소유자와 일치해야 함) |
- **응답 (200):**
    
    ```json
      { "submissionId": "91023", "problemId": "301", "language": "python3", "sourceCode": "…", "judgeAttempt": 1, "submittedAt": "2026-09-28T10:00:00+09:00" }
    ```
    

#### 3.3.4 회원별 PRACTICE AC 목록 (재동기화)

- **Method / URI:** `GET /internal/v1/submissions/accepted`
- **인증:** 서비스 계정 (study, member)
- **대응 테이블:** `submissions`
- **설명:** 복제본 재동기화 관리자 절차 전용. 평상시 흐름에서는 호출하지 않는다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `context` | string | Y | — | `PRACTICE`만 허용 |
    | `userId` | string | Y | — | 회원 ID |
    | `after` | string | N | — | 커서 (마지막 `submissionId`) |
    | `size` | int | N | 100 | 최대 500 |
- **응답 (200):**
    
    ```json
      { "items": [ { "submissionId": "91023", "problemId": "301", "submittedAt": "2026-09-28T10:00:00+09:00", "judgeAttempt": 1 } ], "nextCursor": "91023" }
    ```
    
    - AC만 반환하므로 판정 필드는 두지 않는다. 회원이 없으면 빈 목록

---

## 4. 모의 코테·대회 (contest)

> 작성: 담당 C · 서비스: `contest-service`
> 

### 4.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/studies/{studyId}/exams`, `/api/v1/exams/**`, `/api/v1/contests/**`, `/api/v1/admin/exams/**`, `/api/v1/admin/contests/**` |
| 내부 경로 | `/internal/v1/exams/**`, `/internal/v1/participations/**` |
| 소유 테이블 | 스키마 `contest` |
| 시간 판정 | 모든 시작·종료·저장·제출 유효성은 서버 시각과 `personalEndsAt` 비교로 판정한다. 클라이언트 타이머는 표시용 (응답마다 `serverNow` 포함) |
| 제출 경로 | 접수(`received_at` 기록) → Outbox → SQS `exam-submission-queue` → judge. 결과는 `ExamSubmissionJudged`·`ContestSubmissionJudged`로 받는다 |
| 공개 범위 | 시험: `IN_PROGRESS` 공개 테스트 결과만 → `CLOSED` 잠정 점수·순위(화면) → `FINALIZED` 확정(알림). 대회: 판정 즉시 공개 |
| 순위 | Redis Sorted Set 조회용, 원천은 DB 결과 테이블. 확정 시 DB로 재계산 |
| 이벤트 발행 | `ExamParticipantRegistered`, `ExamStarted`, `ExamClosed`, `ExamFinalized`, `ExamUpdated`, `ExamCanceled`, `Contest*` 동일 계열, `ExamSubmissionRequested`, `ContestSubmissionRequested` |

### 4.1 외부 API — 시험 생성·조회·변경

#### 4.1.1 시험 생성

- **Method / URI:** `POST /api/v1/studies/{studyId}/exams`
- **인증:** 로그인 필요 (`SUSPENDED` 차단) + 해당 스터디 `LEADER`·`MANAGER`
- **대응 테이블:** `exams`, `exam_problems`, `study_membership_replicas`, `contest_resource_guards`, `outbox_events`
- **설명:** 스터디 시험(`FIXED`)을 만들고 각 문제의 현재 공개 회차를 고정한다. `STUDY` 가드가 `PREPARED`/`BLOCKED`이거나 `CLOSED` 스터디면 `EXAM_STUDY_CLOSED`(409). 기본 3문제·90분·100점.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `studyId` | string | Y | Path · 식별자 문자열 |
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `title` | string | Y | 제목 |
    | `mode` | string | Y | FIXED |
    | `startsAt` | string (date-time) | Y | 시작 시각 |
    | `endsAt` | string (date-time) | Y | 전체 종료 시각 |
    | `problems` | object[] | Y | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | Y | 문제 ID 문자열 |
    | `problems[].score` | string | Y | 소수점 둘째 자리 점수 문자열 |
    | `problems[].displayOrder` | integer | Y | 1부터 문제 수까지 연속된 표시 순서 |
    
    ```json
    {
      "title": "10월 1주차 모의 코테",
      "mode": "FIXED",
      "startsAt": "2026-10-20T20:00:00+09:00",
      "endsAt": "2026-10-20T21:30:00+09:00",
      "problems": [
        { "problemId": "101", "score": "30.00", "displayOrder": 1 },
        { "problemId": "102", "score": "30.00", "displayOrder": 2 },
        { "problemId": "103", "score": "40.00", "displayOrder": 3 }
      ]
    }
    ```
    
    - `title` 1~200자, `mode`는 `FIXED`만 허용, `problems` 1~20개 (중복 `problemId` 불가), `displayOrder`는 1부터 문제 수까지 연속, `startsAt`은 현재 이후, `endsAt > startsAt`, 시험 시간 1~180분, `score` 0.01~9999.99 소수점 둘째 자리 문자열, 배점 합계 9999.99 이하
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (201):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examId` | string | 시험 ID 문자열 |
    | `studyId` | string | 스터디 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `examRevision` | string | 시험 설정 변경 버전 문자열 |
    | `version` | string | 조회한 낙관적 락 버전 문자열 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `startsAt` | string (date-time) | 시작 시각 |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `totalScore` | string | 배점 합계 문자열 |
    | `problems` | object[] | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `problems[].revisionNumber` | integer | 문제의 수정 회차 번호 |
    | `problems[].score` | string | 배점 문자열. 0.01~9999.99 |
    | `problems[].displayOrder` | integer | 1부터 문제 수까지 연속된 표시 순서 |
    
    ```json
    {
      "success": true,
      "message": "시험이 생성되었습니다.",
      "data": {
        "examId": "55",
        "studyId": "12",
        "status": "SCHEDULED",
        "examRevision": "1",
        "version": "0",
        "resultRevision": "0",
        "startsAt": "2026-10-20T20:00:00+09:00",
        "endsAt": "2026-10-20T21:30:00+09:00",
        "totalScore": "100.00",
        "problems": [
          {
            "problemId": "101",
            "problemRevisionId": "1001",
            "revisionNumber": 1,
            "score": "30.00",
            "displayOrder": 1
          }
        ]
      }
    }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `EXAM_STUDY_CLOSED`(409), `EXAM_PROBLEM_NOT_AVAILABLE`(409), `CONTEST_DEPENDENCY_UNAVAILABLE`(503), `COMMON_INVALID_REQUEST`(400)
- **외부 의존:**
    - `GET /internal/v1/problems/summaries?ids=` (problem) — `GENERAL`·`PUBLIC` 및 현재 `problemRevisionId` 검증. 실패 시 503
    - `GET /internal/v1/studies/{studyId}/members` (study) — 역할·스터디 상태 검증. 실패 시 503
- **발행:** `ExamParticipantRegistered`는 참가 등록 시 (생성 단계에서는 불필요)

#### 4.1.2 스터디 시험 목록

- **Method / URI:** `GET /api/v1/studies/{studyId}/exams`
- **인증:** 로그인 필요 + `APPROVED` 구성원 또는 해당 시험에 등록한 본인
- **대응 테이블:** `exams`, `exam_participants`
- **설명:** 스터디 소속 시험 목록. 스터디 탈퇴 후에도 본인이 등록한 시험은 조회한다. 시작 전에는 문제 식별자·본문을 반환하지 않는다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `studyId` | string | Y | — | Path · 스터디 ID (식별자 문자열) |
    | `status` | string | N | — | Query · 시험 상태 필터 — 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `page` | int | N | 0 | Query · 0부터 시작 |
    | `size` | int | N | 20 | Query · 최대 100. 기본 20 |
    | `sort` | string | N | `startsAt,desc` | Query · 허용: `startsAt`, `createdAt` |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `content` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `content[].examId` | string | 시험 ID |
    | `content[].title` | string | 제목 |
    | `content[].status` | string | 시험 상태 |
    | `content[].startsAt` | string (date-time) | 시작 시각 |
    | `content[].endsAt` | string (date-time) | 전체 종료 시각 |
    | `content[].problemCount` | integer | 편입된 문제 수 |
    | `content[].myParticipantStatus` | string 또는 null | 본인 참가 상태. 미등록은 null |
    | `page` · `size` · `totalElements` · `totalPages` · `hasNext` | — | 공통 목록 필드 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "content": [
          {
            "examId": "55",
            "title": "10월 1주차 모의 코테",
            "status": "SCHEDULED",
            "startsAt": "2026-10-20T20:00:00+09:00",
            "endsAt": "2026-10-20T21:30:00+09:00",
            "problemCount": 3,
            "myParticipantStatus": null
          }
        ],
        "page": 0,
        "size": 20,
        "totalElements": 1,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `EXAM_NOT_FOUND`(404)

#### 4.1.3 시험 상세

- **Method / URI:** `GET /api/v1/exams/{examId}`
- **인증:** 로그인 필요 + 스터디 `APPROVED` 구성원 또는 활성 등록자
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`
- **설명:** 시작 전에는 문제 식별자·본문을 포함하지 않는다. 상세 조회만으로 입장을 기록하지 않는다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `examId` | string | Y | Path |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examId` | string | 시험 ID 문자열 |
    | `studyId` | string | 스터디 ID 문자열 |
    | `title` | string | 제목 |
    | `mode` | string | FIXED |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `startsAt` | string (date-time) | 시작 시각 |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `totalScore` | string | 배점 합계 문자열 |
    | `problemCount` | integer | 편입된 문제 수 |
    | `examRevision` | string | 시험 설정 변경 버전 문자열 |
    | `version` | string | 조회한 낙관적 락 버전 문자열 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `myParticipation` | object 또는 null | 본인의 활성 참가 정보. 미등록은 null |
    | `myParticipation.participantId` | string | 참가 ID 문자열 |
    | `myParticipation.status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `myParticipation.enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `myParticipation.personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    
    ```json
    	{
    	  "success": true,
    	  "message": null,
    	  "data": {
    	    "examId": "55",
    	    "studyId": "12",
    	    "title": "10월 1주차 모의 코테",
    	    "mode": "FIXED",
    	    "status": "SCHEDULED",
    	    "startsAt": "2026-10-20T20:00:00+09:00",
    	    "endsAt": "2026-10-20T21:30:00+09:00",
    	    "serverNow": "2026-10-10T09:00:00+09:00",
    	    "totalScore": "100.00",
    	    "problemCount": 3,
    	    "examRevision": "1",
    	    "version": "0",
    	    "resultRevision": "0",
    	    "myParticipation": {
    	      "participantId": "411",
    	      "status": "REGISTERED",
    	      "enteredAt": null,
    	      "personalEndsAt": "2026-10-20T21:30:00+09:00"
    	    }
    	  }
    	}
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403)

#### 4.1.4 시험 수정

- **Method / URI:** `PATCH /api/v1/exams/{examId}`
- **인증:** 로그인 필요 + 현재 `LEADER`·`MANAGER`
- **대응 테이블:** `exams`, `exam_problems`, `contest_resource_guards`, `outbox_events`
- **설명:** `SCHEDULED` 상태이고 양쪽 `startsAt`이 모두 서버 시간보다 미래일 때만 허용. 전체 설정을 전달한다. 문제를 다시 선택하면 그 시점 현재 회차로 고정하며 참가자 `personalEndsAt`을 함께 갱신. `examRevision`·`version` +1.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `version` | string | Y | 조회한 낙관적 락 버전 문자열 |
    | `title` | string | Y | 제목 |
    | `startsAt` | string (date-time) | Y | 시작 시각 |
    | `endsAt` | string (date-time) | Y | 전체 종료 시각 |
    | `problems` | object[] | Y | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | Y | 문제 ID 문자열 |
    | `problems[].score` | string | Y | 소수점 둘째 자리 점수 문자열 |
    | `problems[].displayOrder` | integer | Y | 1부터 문제 수까지 연속된 표시 순서 |
    
    ```json
    {
      "version": "0",
      "title": "10월 1주차 모의 코테 (수정)",
      "startsAt": "2026-10-20T20:00:00+09:00",
      "endsAt": "2026-10-20T21:30:00+09:00",
      "problems": [
        { "problemId": "101", "score": "30.00", "displayOrder": 1 },
        { "problemId": "102", "score": "30.00", "displayOrder": 2 },
        { "problemId": "103", "score": "40.00", "displayOrder": 3 }
      ]
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    ```json
    {
      "success": true,
      "message": "시험이 생성되었습니다.",
      "data": {
        "examId": "55",
        "studyId": "12",
        "status": "SCHEDULED",
        "examRevision": "1",
        "version": "0",
        "resultRevision": "0",
        "startsAt": "2026-10-20T20:00:00+09:00",
        "endsAt": "2026-10-20T21:30:00+09:00",
        "totalScore": "100.00",
        "problems": [
          {
            "problemId": "101",
            "problemRevisionId": "1001",
            "revisionNumber": 1,
            "score": "30.00",
            "displayOrder": 1
          }
        ]
      }
    }
    ```
    
    - 시험 생성(4.1.1) 응답 본문 구조와 같음
- **에러:**
    - `EXAM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `EXAM_MODIFY_NOT_ALLOWED`(409), `EXAM_UPDATE_CONFLICT`(409), `EXAM_PROBLEM_NOT_AVAILABLE`(409), `COMMON_INVALID_REQUEST`(400)
- **발행:** `ExamUpdated` — 전체 문제 목록·시간·배점·활성 등록자 ID 포함

#### 4.1.5 시험 취소

- **Method / URI:** `POST /api/v1/exams/{examId}/cancellation`
- **인증:** 로그인 필요 + 현재 `LEADER`·`MANAGER`
- **대응 테이블:** `exams`, `exam_participants`, `exam_participant_histories`, `outbox_events`
- **설명:** `SCHEDULED`에서만 `CANCELED`로 전이. 이미 `CANCELED`면 변경 없이 현재 상태 반환. 참가 이력과 코드·제출은 삭제하지 않는다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 바디:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `version` | string | Y | 조회한 낙관적 락 버전 문자열 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "version": "0",
      "reason": "일정 변경으로 인한 취소"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examId` | string | 시험 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `canceledAt` | string (date-time) | 취소 시각 |
    | `examRevision` | string | 시험 설정 변경 버전 문자열 |
    | `version` | string | 조회한 낙관적 락 버전 문자열 |
    
    ```json
    	{
    	  "success": true,
    	  "message": "시험이 취소되었습니다.",
    	  "data": {
    	    "examId": "55",
    	    "status": "CANCELED",
    	    "canceledAt": "2026-10-10T09:00:00+09:00",
    	    "examRevision": "2",
    	    "version": "1"
    	  }
    	}
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `EXAM_MODIFY_NOT_ALLOWED`(409), `EXAM_UPDATE_CONFLICT`(409)
- **발행:** `ExamCanceled` — 취소 시각·사유·활성 등록자 ID 스냅샷 포함

### 4.2 외부 API — 시험 참가·Draft·제출·결과

#### 4.2.1 시험 참가 등록

- **Method / URI:** `POST /api/v1/exams/{examId}/participants`
- **인증:** 로그인 필요 (`SUSPENDED` 차단) + `ACTIVE` 회원이며 등록 시 `APPROVED` 구성원 복제본이 있어야 한다. 없으면 `EXAM_NOT_ELIGIBLE`(403)
- **대응 테이블:** `exams`, `exam_participants`, `exam_participant_histories`, `study_membership_replicas`, `contest_resource_guards`, `outbox_events`
- **설명:** 종료 전 등록 가능. 기존 활성 참가가 있으면 `200`으로 기존 반환, 새 등록은 `201`. `CANCELED` 문맥에는 등록 불가.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200/201):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `examId` | string | 시험 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": "참가 등록이 완료되었습니다.",
      "data": {
        "participantId": "411",
        "examId": "55",
        "status": "REGISTERED",
        "enteredAt": null,
        "personalEndsAt": "2026-10-20T21:30:00+09:00",
        "serverNow": "2026-10-10T09:00:00+09:00",
        "participantRevision": "1"
      }
    }
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_ELIGIBLE`(403), `EXAM_CLOSED`(409), `EXAM_CANCELED`(409)
- **동시성 보장:** `MEMBER` 가드·시험·참가자 순으로 잠금, 활성 참가 1건 보장
- **발행:** `ExamParticipantRegistered` — 새 등록 시(`201`)만

#### 4.2.2 시험 입장

- **Method / URI:** `POST /api/v1/exams/{examId}/start`
- **인증:** 로그인 필요 + 활성 등록 본인. `startsAt <= 서버 시간 <= personalEndsAt`이고 개인 종료 전
- **대응 테이블:** `exams`, `exam_participants`, `exam_participant_histories`, `contest_resource_guards`, `member_replicas`
- **설명:** 최초 `enteredAt` 기록·`STARTED` 전이. 재입장은 최초 `enteredAt` 유지. 등록 후 스터디 탈퇴해도 입장·제출 자격은 유지.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
- **비고:**
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    
    ```json
    	{
    	  "success": true,
    	  "message": null,
    	  "data": {
    	    "participantId": "411",
    	    "status": "STARTED",
    	    "enteredAt": "2026-10-20T20:00:30+09:00",
    	    "personalEndsAt": "2026-10-20T21:30:00+09:00",
    	    "serverNow": "2026-10-20T20:00:30+09:00",
    	    "participantRevision": "2"
    	  }
    	}
    ```
    
- **동시성 보장:** `MEMBER` 가드·시험·참가자 잠금
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_NOT_STARTED`(403), `EXAM_CLOSED`(409), `EXAM_ALREADY_FINISHED`(409), `CONTEST_OPERATION_BLOCKED`(409)

#### 4.2.3 시험 문제 목록·본문

- **Method / URI:** `GET /api/v1/exams/{examId}/problems`
- **인증:** 로그인 필요 + 입장한 본인. 시작 전 `EXAM_NOT_STARTED`(403). 문제 조회만으로 입장시키지 않는다.
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`
- **설명:** B의 고정 회차 본문 계약으로 조회. 숨김 테스트·정답 코드는 포함하지 않는다. 종료 후 본인 복기도 같은 회차를 사용.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examId` | string | 시험 ID 문자열 |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `problems` | object[] | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `problems[].revisionNumber` | integer | 문제의 수정 회차 번호 |
    | `problems[].title` | string | 제목 |
    | `problems[].score` | string 또는 null | 소수점 둘째 자리 점수 문자열 |
    | `problems[].displayOrder` | integer | 1부터 문제 수까지 연속된 표시 순서 |
    | `problems[].body` | string | description·constraints·examples를 조합한 화면 본문 |
    | `problems[].functionSpec` | object | 고정 회차의 함수 명세. B 공통 데이터 형식 참조 |
    | `problems[].executionLimits` | object | 고정 회차의 실행 제한. B 공통 데이터 형식 참조 |
    | `problems[].sampleTests` | object[] | B samples를 변환한 공개 실행 예제 목록 |
    | `problems[].imageIds` | string[] | 고정 회차 본문에 연결된 이미지 ID 목록 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "examId": "55",
        "personalEndsAt": "2026-10-20T21:30:00+09:00",
        "serverNow": "2026-10-20T20:05:00+09:00",
        "problems": [
          {
            "problemId": "101",
            "problemRevisionId": "1001",
            "revisionNumber": 1,
            "title": "두 수의 합",
            "score": "30.00",
            "displayOrder": 1,
            "body": "## 문제\n두 정수 a와 b의 합을 반환하세요.",
            "functionSpec": { "name": "solution", "parameters": [{ "name": "a", "type": { "kind": "INT32" } }], "returnType": { "kind": "INT32" } },
            "executionLimits": { "timeMs": 2000, "memoryKb": 262144, "wallTimeMs": 7000 },
            "sampleTests": [{ "testAssetId": "501", "argumentTexts": { "a": "1", "b": "2" }, "expectedReturnText": "3" }],
            "imageIds": ["701"]
          }
        ]
      }
    }
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_NOT_STARTED`(403), `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:**
    - `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content` (problem) — 실패 시 503

#### 4.2.4 시험 Draft 저장

- **Method / URI:** `PUT /api/v1/exams/{examId}/drafts/{problemId}`
- **인증:** 로그인 필요 + `ACTIVE`·`STARTED` 본인
- **Rate Limit:** 참가자당 초당 1회 (초과 요청은 마지막 요청만 반영하는 서버 버퍼 처리)
- **대응 테이블:** `exams`, `exam_participants`, `exam_drafts`, `member_replicas`
- **설명:** 참가자 잠금 아래 문제의 전체 언어 최대 `seq`보다 큰 경우 해당 언어 Draft를 저장. 같거나 작으면 무시하고 `applied: false` 반환. 직접 제출은 이 버퍼를 우회해 최신 코드를 트랜잭션에서 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `language` | string | Y | JAVA / PYTHON / CPP |
    | `sourceCode` | string | Y | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `seq` | string | Y | 문제별 전체 언어 공통 증가 순번 문자열 |
    
    ```json
    {
      "language": "java",
      "sourceCode": "class Solution { public int solution(int a, int b) { return a + b; } }",
      "seq": "15"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `applied` | boolean | 신규 Draft가 실제 저장됐는지 |
    | `serverSeq` | string | 서버의 전체 언어 최대 순번 문자열 |
    | `savedAt` | string (date-time) | 서버 Draft 저장 시각 |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "applied": true,
        "serverSeq": "15",
        "savedAt": "2026-10-20T20:30:58+09:00",
        "personalEndsAt": "2026-10-20T21:30:00+09:00",
        "serverNow": "2026-10-20T20:30:58+09:00"
      }
    }
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_CLOSED`(409), `EXAM_CANCELED`(409), `CODE_TOO_LARGE`(413), `COMMON_INVALID_REQUEST`(400)

#### 4.2.5 시험 직접 제출

- **Method / URI:** `POST /api/v1/exams/{examId}/submissions`
- **인증:** 신규 접수는 `ACTIVE`·`STARTED` 등록 본인. 이미 접수한 동일 요청은 `FINISHED`·`SUSPENDED`에서도 반환
- **Idempotency:** 같은 `(participantId, problemId, seq)` 기존 제출이면 동일 본문은 기존 `examSubmissionId` 반환, 다른 본문은 `IDEMPOTENCY_KEY_CONFLICT`(422)
- **Rate Limit:** 회원당 분당 10회, 같은 문제 5초 간격
- **대응 테이블:** `exams`, `exam_participants`, `exam_problems`, `exam_drafts`, `exam_submissions`, `member_replicas`, `outbox_events`
- **설명:** `receivedAt`은 참가자 행 잠금 획득 직후 서버에서 기록. 신규 제출은 시간·언어·고정 회차 검증 후 Draft·`exam_submissions`·Outbox를 한 트랜잭션에 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `problemId` | string | Y | 문제 ID 문자열 |
    | `language` | string | Y | JAVA / PYTHON / CPP |
    | `sourceCode` | string | Y | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `seq` | string | Y | 문제별 전체 언어 공통 증가 순번 문자열 |
    
    ```json
    {
      "problemId": "101",
      "language": "java",
      "sourceCode": "class Solution { public int solution(int a, int b) { return a + b; } }",
      "seq": "16"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (202):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examSubmissionId` | string | C 시험 접수 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `receivedAt` | string (date-time) | 잠금 안에서 기록한 서버 접수 시각 |
    | `draftSeq` | string | 접수에 사용한 Draft 순번 문자열 |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    
    ```json
    {
      "success": true,
      "message": "제출이 접수되었습니다.",
      "data": {
        "examSubmissionId": "4001",
        "status": "ACCEPTED",
        "receivedAt": "2026-10-20T21:29:59.123456+09:00",
        "draftSeq": "16",
        "personalEndsAt": "2026-10-20T21:30:00+09:00"
      }
    }
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_CLOSED`(409), `EXAM_CANCELED`(409), `EXAM_ALREADY_FINISHED`(409), `DRAFT_SEQ_CONFLICT`(409), `IDEMPOTENCY_KEY_CONFLICT`(422), `SUBMISSION_RATE_LIMITED`(429), `CODE_TOO_LARGE`(413), `UNSUPPORTED_LANGUAGE`(400)
- **발행:** `ExamSubmissionRequested`

#### 4.2.6 시험 수동 종료

- **Method / URI:** `POST /api/v1/exams/{examId}/finish`
- **인증:** 로그인 필요 + 입장 본인
- **대응 테이블:** `exams`, `exam_participants`, `exam_participant_histories`, `exam_drafts`, `exam_submissions`, `outbox_events`
- **설명:** 참가자 잠금 아래 `personalEndsAt = min(현재 값, 서버 시간)` 확정 후 같은 트랜잭션에서 최신 Draft 자동 제출·`autoSubmittedAt`·`FINISHED`·상태 이력 저장. 실패 시 전부 롤백. `FINISHED` 재요청은 기존 값 반환. 수동 종료는 개인 마감이며 시험 전체 `CLOSED`/힌트 차단 해제가 아님.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `autoSubmittedAt` | string (date-time) | 개인 자동 제출 처리 완료 시각 |
    | `autoSubmissionIds` | string[] | 수동 종료 시 생성한 자동 접수 ID 목록 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "participantId": "411",
        "status": "FINISHED",
        "personalEndsAt": "2026-10-20T21:10:00+09:00",
        "autoSubmittedAt": "2026-10-20T21:10:00+09:00",
        "autoSubmissionIds": ["4002", "4003"]
      }
    }
    ```
    
- **에러:**
    - `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_NOT_STARTED`(403)
- **발행:** `ExamSubmissionRequested` — 자동 제출 문제별

#### 4.2.7 내 시험 결과

- **Method / URI:** `GET /api/v1/exams/{examId}/results/me`
- **인증:** 로그인 필요 + 등록 본인
- **대응 테이블:** `exams`, `exam_participants`, `exam_submissions`, `exam_problem_results`, `result_snapshots`
- **설명:** 시험 전체 `CLOSED` 전에는 `score`/`rank`/`progress`=null, `problems`는 빈 배열. `CLOSED` 이후 잠정 결과(`PROVISIONAL`), `FINALIZED` 이후 확정 결과. 개인 수동 종료 후에도 전체 종료 전까지 같은 제한 적용.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `resultStatus` | string | HIDDEN / PROVISIONAL / FINALIZED. 문맥별 공개 규칙 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `score` | string 또는 null | 소수점 둘째 자리 점수 문자열 |
    | `rank` | integer 또는 null | 공동 순위. 미집계는 null |
    | `isRanked` | boolean | 순위 집계 대상 여부 |
    | `progress` | object | 종결 제출 수·전체 접수 제출 수 |
    | `progress.terminalCount` | integer | 종결 제출 수 |
    | `progress.totalSubmissionCount` | integer | 전체 유효 접수 제출 수 |
    | `problems` | object[] | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].lastValidExamSubmissionId` | string 또는 null | 마지막 유효 시험 접수 ID. 미제출은 null |
    | `problems[].verdict` | string 또는 null | 최종 판정. 미산정·공개 제한은 null |
    | `problems[].score` | string 또는 null | 소수점 둘째 자리 점수 문자열 |
    | `problems[].passedCount` | integer 또는 null | 통과 테스트 수. 미산정·공개 제한은 null |
    | `problems[].totalCount` | integer 또는 null | 전체 테스트 수. 미산정·공개 제한은 null |
    | `problems[].pending` | boolean | 마지막 유효 제출의 채점 미종결 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "resultStatus": "PROVISIONAL",
        "resultRevision": "3",
        "score": "85.00",
        "rank": 1,
        "isRanked": true,
        "progress": {
          "terminalCount": 3,
          "totalSubmissionCount": 5
        },
        "problems": [
          {
            "problemId": "101",
            "lastValidExamSubmissionId": "4001",
            "verdict": "AC",
            "score": "30.00",
            "passedCount": 12,
            "totalCount": 12,
            "pending": false
          }
        ]
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403)

#### 4.2.8 시험 결과 순위

- **Method / URI:** `GET /api/v1/exams/{examId}/results`
- **인증:** 로그인 필요 + 활성 등록자 또는 현재 스터디 구성원
- **대응 테이블:** `exams`, `exam_participants`, `exam_problem_results`, `member_replicas`, `result_snapshots`
- **설명:** `CLOSED` 전 `EXAM_RESULT_NOT_AVAILABLE`(409). `isRanked=true`만 포함, 총점 내림차순·마지막 유효 제출 시간 오름차순, 완전 동점 공동 순위(1,1,3). `resultRevision`에 묶인 커서 페이지네이션이며 버전이 달라지면 `RESULT_CURSOR_EXPIRED`(409).
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `resultStatus` | string | HIDDEN / PROVISIONAL / FINALIZED. 문맥별 공개 규칙 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].memberId` | string | 회원 ID 문자열 |
    | `items[].nickname` | string | 회원 표시 이름. 탈퇴자는 익명화 |
    | `items[].score` | string 또는 null | 소수점 둘째 자리 점수 문자열 |
    | `items[].rank` | integer 또는 null | 공동 순위. 미집계는 null |
    | `items[].pending` | boolean | 마지막 유효 제출의 채점 미종결 여부 |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "resultStatus": "PROVISIONAL",
        "resultRevision": "3",
        "content": [
          {
            "memberId": "7",
            "nickname": "kim-dev",
            "score": "85.00",
            "rank": 1,
            "pending": false
          }
        ],
        "page": 0,
        "size": 20,
        "totalElements": 12,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_RESULT_NOT_AVAILABLE`(409), `RESULT_CURSOR_EXPIRED`(409)

#### 4.2.9 내 시험 제출 내역

- **Method / URI:** `GET /api/v1/exams/{examId}/submissions`
- **인증:** 로그인 필요 + 등록 본인
- **대응 테이블:** `exams`, `exam_participants`, `exam_submissions`, `exam_problem_results`
- **설명:** `CLOSED` 전 `status`는 `ACCEPTED`/`REQUESTED`까지만 표시하며 `verdict`/`score`/통과 수는 `null`. `FAILED`는 처리 장애 표시만.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | N | — | Query · 문제 ID 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].examSubmissionId` | string | C 시험 접수 ID 문자열 |
    | `items[].problemId` | string | 문제 ID 문자열 |
    | `items[].draftSeq` | string | 접수에 사용한 Draft 순번 문자열 |
    | `items[].submissionType` | string | MANUAL / AUTO |
    | `items[].receivedAt` | string (date-time) | 잠금 안에서 기록한 서버 접수 시각 |
    | `items[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `items[].verdict` | string 또는 null | 최종 판정. 미산정·공개 제한은 null |
    | `items[].score` | string 또는 null | 소수점 둘째 자리 점수 문자열 |
    | `items[].passedCount` | integer 또는 null | 통과 테스트 수. 미산정·공개 제한은 null |
    | `items[].totalCount` | integer 또는 null | 전체 테스트 수. 미산정·공개 제한은 null |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "content": [
          {
            "examSubmissionId": "4001",
            "problemId": "101",
            "draftSeq": "16",
            "submissionType": "MANUAL",
            "receivedAt": "2026-10-20T21:29:59.123456+09:00",
            "status": "JUDGED",
            "verdict": "AC",
            "score": "30.00",
            "passedCount": 12,
            "totalCount": 12
          }
        ],
        "page": 0,
        "size": 20,
        "totalElements": 3,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403)

#### 4.2.10 시험 참가 취소

- **Method / URI:** `POST /api/v1/exams/{examId}/participants/me/cancellation`
- **인증:** 로그인 필요 (회원·문맥 권한 적용)
- **대응 테이블:** `exams`, `exam_participants`, `exam_participant_histories`, `outbox_events`
- **설명:** 시작 전 `REGISTERED`만 `CANCELED`로 전이. 이미 취소됐으면 기존 취소 반환. 시작 이후 `EXAM_PARTICIPATION_CANCEL_NOT_ALLOWED`(409).
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": "참가가 취소되었습니다.",
      "data": {
        "participantId": "411",
        "status": "CANCELED",
        "participantRevision": "2"
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_PARTICIPATION_CANCEL_NOT_ALLOWED`(409)
- **발행:** `ExamParticipantCanceled`

#### 4.2.11 시험 Draft 조회

- **Method / URI:** `GET /api/v1/exams/{examId}/drafts/me`
- **인증:** 로그인 필요 + 입장 본인. 종료 후에도 읽기 가능.
- **대응 테이블:** `exams`, `exam_participants`, `exam_drafts`
- **설명:** 재진입 시 문제·언어별 서버 Draft와 `seq` 반환. 클라이언트는 LocalStorage와 비교해 `seq`가 큰 쪽을 보여주고, LocalStorage가 크면 즉시 시험  Draft 저장(4.2.4)으로 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `personalEndsAt` | string (date-time) | 개인 제출 마감 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].problemId` | string | 문제 ID 문자열 |
    | `items[].language` | string | JAVA / PYTHON / CPP |
    | `items[].sourceCode` | string | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `items[].seq` | string | 문제별 전체 언어 공통 증가 순번 문자열 |
    | `items[].savedAt` | string (date-time) | 서버 Draft 저장 시각 |
    | `problemSequences` | object[] | 문제별 전체 언어 최고 seq 목록 |
    | `problemSequences[].problemId` | string | 문제 ID 문자열 |
    | `problemSequences[].serverSeq` | string | 서버의 전체 언어 최대 순번 문자열 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "participantId": "411",
        "personalEndsAt": "2026-10-20T21:30:00+09:00",
        "serverNow": "2026-10-20T20:31:00+09:00",
        "content": [
          {
            "problemId": "101",
            "language": "java",
            "sourceCode": "class Solution { ... }",
            "seq": "15",
            "savedAt": "2026-10-20T20:30:58+09:00"
          }
        ],
        "problemSequences": [
          { "problemId": "101", "serverSeq": "15" },
          { "problemId": "102", "serverSeq": "0" }
        ]
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_NOT_STARTED`(403)

#### 4.2.12 시험 문제 이미지

- **Method / URI:** `GET /api/v1/exams/{examId}/problems/{problemId}/images/{imageId}`
- **인증:** 로그인 필요 + 입장한 본인. 시작 전 `EXAM_NOT_STARTED`(403). 문제 조회만으로 입장시키지 않는다.
    - 시험 문제 목록·본문(4.2.3)과 동일
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`
- **설명:** 고정 `problemRevisionId`에 연결된 이미지인지 C와 B가 검증. 연결되지 않은 이미지는 404.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
    | `imageId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `image/*` | 원본 이미지 MIME 타입 |
    | `Cache-Control` | `private, no-store` | 응답 캐시 금지 |
- **응답  (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | 이미지 | binary | 고정 회차에 연결된 원본 이미지. JSON 래퍼 없음 |
    - 이미지 바이너리 (`Content-Type: image/*`, `Cache-Control: private, no-store`
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_STARTED`(403), `EXAM_NOT_REGISTERED`(403), `PROBLEM_NOT_FOUND`(404)
- **외부 의존:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}` (problem)

#### 4.2.13 시험 Run

- **Method / URI:** `POST /api/v1/exams/{examId}/problems/{problemId}/runs`
- **인증:** 로그인 필요 + `ACTIVE`·입장 본인·접수 시간 안. 종료 후 신규 Run은 `EXAM_CLOSED`(409).
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`, `member_replicas`
- **설명:** B의 스터디의 예정·진행 중 시험 조회(4.6.3) Run 계약에 전달. Draft·제출·점수·통계를 저장하거나 이벤트를 발행하지 않음. 공개 예제만 실행하며 숨김 테스트는 실행하지 않음. 실행 기한 30초, 초과 `504 RUN_TIMEOUT`. 실행 중 재요청 `429 RUN_IN_PROGRESS`.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `language` | string | Y | JAVA / PYTHON / CPP |
    | `sourceCode` | string | Y | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `customTests` | object[] | N | 선택 사용자 입력. 최대 10개 |
    | `customTests[].args` | JSON value[] | Y | 사용자 입력 인수 배열 |
    
    ```json
    {
      "language": "java",
      "sourceCode": "class Solution { public int solution(int a, int b) { return a + b; } }",
      "customTests": [
        { "args": [3, 5] }
      ]
    }
    ```
    
    - `customTests` 최대 10개
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `results` | object[] | 공개 예제·사용자 입력 실행 결과 목록 |
    | `results[].type` | string | SAMPLE / CUSTOM 실행 결과 구분 |
    | `results[].index` | integer | 공개 예제 또는 사용자 입력 목록의 0부터 시작하는 순번 |
    | `results[].output` | string | 공개 예제·사용자 입력의 실행 출력 |
    | `results[].error` | string 또는 null | 실행 오류. 오류가 없으면 null |
    | `results[].timeMs` | integer | 실행 시간(ms) |
    | `results[].memoryKb` | integer | 최대 사용 메모리(KB) |
    | `completedAt` | string (date-time) | Run 완료 시각 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "results": [
          { "type": "SAMPLE", "index": 0, "output": "3", "error": null, "timeMs": 150, "memoryKb": 8192 },
          { "type": "CUSTOM", "index": 0, "output": "8", "error": null, "timeMs": 148, "memoryKb": 8192 }
        ],
        "completedAt": "2026-10-20T20:15:00+09:00"
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403), `EXAM_NOT_STARTED`(403), `EXAM_CLOSED`(409), `RUN_IN_PROGRESS`(429), `RUN_TIMEOUT`(504), `CODE_TOO_LARGE`(413), `UNSUPPORTED_LANGUAGE`(400)
- **외부 의존:** `POST /internal/v1/judge/runs` (judge) — 실패 시 503

### 4.3 외부 API — 시험 실시간 결과

#### 4.3.1 시험 결과 SSE

- **Method / URI:** `GET /api/v1/exams/{examId}/events`
- **인증:** 로그인 필요 + 활성 등록 본인
- **대응 테이블:** `exams`, `exam_participants`, `exam_problem_results`, `result_snapshots`
- **설명:** 전체 `CLOSED` 전에는 시험 상태·서버 시각만 전송. 결과 이벤트는 `CLOSED` 이후 `PROVISIONAL` 또는 `FINALIZED`만 반환. 재연결 시 `Last-Event-ID`로 복구. heartbeat 30초, 60초마다 정상 종료 후 재연결.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
    | `Last-Event-ID` | N | `EXAM:501:3` | 재연결 시 마지막 수신 결과 버전 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `text/event-stream` | 응답 본문 형식 |
    | `Cache-Control` | `private, no-store` | 응답 캐시 금지 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `id` | string | 문맥:ID:resultRevision. 상태 이벤트는 본문 상태로 복구 |
    | `event` | string | 해당 문맥의 상태·결과 변경 이벤트 |
    | `data` | object | 아래 이벤트별 필드를 포함한 JSON 객체 |
- **응답 이벤트 필드:**
    
    
    | 이벤트 | 필드 | 타입 | 설명 |
    | --- | --- | --- | --- |
    | exam-state-updated / exam-result-updated | `examId` | string | 시험 ID |
    | exam-state-updated | `status` | string | 시험 상태 |
    | exam-state-updated | `serverTime` | string (date-time) | C 서버 시각 |
    | exam-result-updated | `resultStatus` | string | PROVISIONAL / FINALIZED |
    | exam-result-updated | `resultRevision` | string | DB 결과 버전 |
    | exam-result-updated | `terminalCount` | integer | 해당 본인 종결 접수 수 |
    | exam-result-updated | `totalSubmissionCount` | integer | 해당 본인 유효 접수 수 |
    
    ```
    event: exam-state-updated
    id: EXAM:55:0
    data: {"examId":"55","status":"IN_PROGRESS","serverNow":"2026-10-20T20:00:00+09:00"}
    
    event: exam-result-updated
    id: EXAM:55:3
    data: {"examId":"55","resultStatus":"PROVISIONAL","resultRevision":"3","terminalCount":3,"totalSubmissionCount":5}
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_NOT_REGISTERED`(403)

### 4.4 외부 API — 대회

#### 4.4.1 대회 목록

- **Method / URI:** `GET /api/v1/contests`
- **인증:** 공개 (로그인 선택)
- **대응 테이블:** `contests`
- **설명:** 시작 전 문제 식별자·본문 없음.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | N | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | Query · 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | 대회 상태 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 | 최대 100 |
    | `sort` | string | N | `startsAt,desc` |  |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].contestId` | string | 대회 ID 문자열 |
    | `items[].title` | string | 제목 |
    | `items[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `items[].startsAt` | string (date-time) | 시작 시각 |
    | `items[].endsAt` | string (date-time) | 전체 종료 시각 |
    | `items[].problemCount` | integer | 편입된 문제 수 |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "content": [
          {
            "contestId": "8",
            "title": "Re:Solve 10월 오픈 대회",
            "status": "SCHEDULED",
            "startsAt": "2026-10-31T20:00:00+09:00",
            "endsAt": "2026-10-31T22:00:00+09:00",
            "problemCount": 5
          }
        ],
        "page": 0,
        "size": 20,
        "totalElements": 3,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    

#### 4.4.2 대회 상세

- **Method / URI:** `GET /api/v1/contests/{contestId}`
- **인증:** 공개 (로그인 선택)
- **대응 테이블:** `contests`
- **설명:** 시작 전 문제 식별자·본문은 반환하지 않음.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | N | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `contestId` | string | 대회 ID 문자열 |
    | `title` | string | 제목 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `startsAt` | string (date-time) | 시작 시각 |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `problemCount` | integer | 편입된 문제 수 |
    | `isProblemsPublicAfterEnd` | boolean | 대회 종료 후 로그인 회원의 문제 열람 허용 여부 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "contestId": "8",
        "title": "Re:Solve 10월 오픈 대회",
        "status": "RUNNING",
        "startsAt": "2026-10-31T20:00:00+09:00",
        "endsAt": "2026-10-31T22:00:00+09:00",
        "serverNow": "2026-10-31T20:10:00+09:00",
        "problemCount": 5,
        "isProblemsPublicAfterEnd": true,
        "resultRevision": "0"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404)

#### 4.4.3 대회 참가 등록

- **Method / URI:** `POST /api/v1/contests/{contestId}/participants`
- **인증:** 로그인 필요 (`SUSPENDED` 차단) + `ACTIVE` 회원
- **대응 테이블:** `contests`, `contest_participants`, `contest_participant_histories`, `contest_resource_guards`, `member_replicas`
- **설명:** `ACTIVE` 회원, 종료 전. `MEMBER` 가드·대회·참가자 잠금, 활성 참가 1건. 기존 활성 등록은 `200`, 새 등록은 `201`.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200 / 201):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    
    ```json
    {
      "success": true,
      "message": "참가 등록이 완료되었습니다.",
      "data": {
        "participantId": "801",
        "status": "REGISTERED",
        "enteredAt": null,
        "participantRevision": "1",
        "endsAt": "2026-10-31T22:00:00+09:00",
        "serverNow": "2026-10-20T09:00:00+09:00"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_CLOSED`(409), `CONTEST_CANCELED`(409)
- **발행:** `ContestParticipantRegistered` — 새 등록 시(`201`)만

#### 4.4.4 대회 참가 취소

- **Method / URI:** `POST /api/v1/contests/{contestId}/participants/me/cancellation`
- **인증:** 로그인 필요 (회원·문맥 권한 적용)
- **대응 테이블:** `contests`, `contest_participants`, `contest_participant_histories`, `outbox_events`
- **설명:** 시작 전 `REGISTERED`만 취소. 재요청 기존 반환. 재등록 새 행.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": "참가가 취소되었습니다.",
      "data": {
        "participantId": "801",
        "status": "CANCELED",
        "participantRevision": "2"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_PARTICIPATION_CANCELLATION_CLOSED`(409)
- **발행:** `ContestParticipantCanceled`

#### 4.4.5 대회 입장

- **Method / URI:** `POST /api/v1/contests/{contestId}/start`
- **인증:** 로그인 필요 + `ACTIVE` 활성 등록 본인. `startsAt <= 서버 시간 <= endsAt`
- **대응 테이블:** `contests`, `contest_participants`, `contest_participant_histories`, `contest_resource_guards`, `member_replicas`
- **설명:** `MEMBER` 가드·대회·참가자 잠금 아래 `enteredAt` 최초 기록·`STARTED`·상태 이력. 재입장은 기존 시간 반환.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `participantId` | string | 참가 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    | `participantRevision` | string | 참가 상태 변경 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "participantId": "801",
        "status": "STARTED",
        "enteredAt": "2026-10-31T20:00:30+09:00",
        "endsAt": "2026-10-31T22:00:00+09:00",
        "serverNow": "2026-10-31T20:00:30+09:00",
        "participantRevision": "2"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_NOT_STARTED`(403), `CONTEST_CLOSED`(409)

#### 4.4.6 대회 문제 목록·본문

- **Method / URI:** `GET /api/v1/contests/{contestId}/problems`
- **인증:** 로그인 필요 + 시작 후 실제 입장 본인. `ENDED` 이후 `isProblemsPublicAfterEnd=true`이면 로그인 회원도 읽기 가능 (입장 기록 없음).
- **대응 테이블:** `contests`, `contest_problems`, `contest_participants`
- **설명:** 숨김 테스트 없음. `EXAM_ONLY` 문제를 `GENERAL`로 전환하지 않음.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `problems` | object[] | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `problems[].revisionNumber` | integer | 문제의 수정 회차 번호 |
    | `problems[].label` | string | 문제 라벨 A~T. 대회 안에서 중복 불가 |
    | `problems[].title` | string | 제목 |
    | `problems[].displayOrder` | integer | 1부터 문제 수까지 연속된 표시 순서 |
    | `problems[].body` | string | description·constraints·examples를 조합한 화면 본문 |
    | `problems[].functionSpec` | object | 고정 회차의 함수 명세. B 공통 데이터 형식 참조 |
    | `problems[].executionLimits` | object | 고정 회차의 실행 제한. B 공통 데이터 형식 참조 |
    | `problems[].sampleTests` | object[] | B samples를 변환한 공개 실행 예제 목록 |
    | `problems[].imageIds` | string[] | 고정 회차 본문에 연결된 이미지 ID 목록 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "problems": [
          {
            "problemId": "601",
            "problemRevisionId": "2001",
            "revisionNumber": 1,
            "label": "A",
            "title": "두 수의 합",
            "displayOrder": 1,
            "body": "## 문제\n...",
            "functionSpec": { "name": "solution", "parameters": [], "returnType": { "kind": "INT32" } },
            "executionLimits": { "timeMs": 2000, "memoryKb": 262144, "wallTimeMs": 7000 },
            "sampleTests": [{ "testAssetId": "601", "argumentTexts": { "a": "1", "b": "2" }, "expectedReturnText": "3" }],
            "imageIds": []
          }
        ]
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_NOT_STARTED`(403), `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content` (problem)

#### 4.4.7 대회 제출

- **Method / URI:** `POST /api/v1/contests/{contestId}/submissions`
- **인증:** 신규 접수는 `ACTIVE`·실제 입장 본인. 이미 접수한 동일 요청은 `SUSPENDED`에서도 반환
- **Idempotency:** `Idempotency-Key` 헤더 필수 (1~100자 ASCII, 대소문자 구분). 같은 키·동일 본문은 기존 반환, 다른 본문은 `IDEMPOTENCY_KEY_CONFLICT`(422). 동일 요청 `requestHash`는 `problemId`·`language`·UTF-8 `sourceCode`의 SHA-256
- **Rate Limit:** 회원당 분당 10회, 같은 문제 5초 간격
- **대응 테이블:** `contests`, `contest_problems`, `contest_participants`, `contest_submissions`, `member_replicas`, `outbox_events`
- **설명:** `receivedAt`은 서버 시간 `<= endsAt`이면 접수. 접수·Outbox 원자 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
    | `Idempotency-Key` | Y | `contest-request-001` | 1~100자 ASCII. 대소문자 구분; 같은 본문 재요청에 재사용 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `problemId` | string | Y | 문제 ID 문자열 |
    | `language` | string | Y | JAVA / PYTHON / CPP |
    | `sourceCode` | string | Y | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    
    ```json
    {
      "problemId": "601",
      "language": "java",
      "sourceCode": "class Solution { public int solution(int a, int b) { return a + b; } }"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (202):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `contestSubmissionId` | string | C 대회 접수 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `receivedAt` | string (date-time) | 잠금 안에서 기록한 서버 접수 시각 |
    
    ```json
    {
      "success": true,
      "message": "제출이 접수되었습니다.",
      "data": {
        "contestSubmissionId": "9001",
        "status": "ACCEPTED",
        "receivedAt": "2026-10-31T20:30:00.123456+09:00"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_CLOSED`(409), `CONTEST_CANCELED`(409), `IDEMPOTENCY_KEY_CONFLICT`(422), `SUBMISSION_RATE_LIMITED`(429), `CODE_TOO_LARGE`(413), `UNSUPPORTED_LANGUAGE`(400)
- **발행:** `ContestSubmissionRequested`

#### 4.4.8 대회 순위

- **Method / URI:** `GET /api/v1/contests/{contestId}/leaderboard`
- **인증:** 공개 (로그인 선택)
- **대응 테이블:** `contests`, `contest_participants`, `contest_problem_results`, `member_replicas`, `result_snapshots`
- **설명:** 제출 1건 이상 활성 참가자만. solved 내림차순·penalty 오름차순·`lastAcceptedAt` 오름차순, 동점 공동 순위. Redis 장애/버전 불일치면 DB 조회, `FINALIZED`는 스냅샷 원천. `resultRevision`에 묶인 커서 페이지네이션.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | N | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 50 | 최대 100 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `resultStatus` | string | HIDDEN / PROVISIONAL / FINALIZED. 문맥별 공개 규칙 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].memberId` | string | 회원 ID 문자열 |
    | `items[].nickname` | string | 회원 표시 이름. 탈퇴자는 익명화 |
    | `items[].solvedCount` | integer | ICPC 해결 문제 수 |
    | `items[].penaltyMinutes` | integer | ICPC 패널티 분 |
    | `items[].lastAcceptedAt` | string (date-time) 또는 null | 순위 기준 마지막 최초 AC 접수 시각. AC가 없으면 null |
    | `items[].rank` | integer 또는 null | 공동 순위. 미집계는 null |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "resultStatus": "PROVISIONAL",
        "resultRevision": "5",
        "content": [
          {
            "memberId": "7",
            "nickname": "kim-dev",
            "solvedCount": 3,
            "penaltyMinutes": 142,
            "lastAcceptedAt": "2026-10-31T21:45:00+09:00",
            "rank": 1
          }
        ],
        "page": 0,
        "size": 50,
        "totalElements": 34,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `RESULT_CURSOR_EXPIRED`(409)

#### 4.4.9 내 대회 제출·결과

- **Method / URI:** `GET /api/v1/contests/{contestId}/results/me`
- **인증:** 로그인 필요 + 등록 본인
- **대응 테이블:** `contests`, `contest_participants`, `contest_submissions`, `contest_problem_results`, `result_snapshots`
- **설명:** 진행 중에도 본인 최종 판정 공개. 숨김 케이스 입력/출력·정답 코드 없음. `FAILED`는 `SYSTEM_ERROR` 처리 상태로 표시하며 오답 패널티에 포함하지 않음.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `page` | int | N | 0 | 제출 목록 페이지 |
    | `size` | int | N | 20 | 최대 100 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `resultStatus` | string | HIDDEN / PROVISIONAL / FINALIZED. 문맥별 공개 규칙 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `solvedCount` | integer | ICPC 해결 문제 수 |
    | `penaltyMinutes` | integer | ICPC 패널티 분 |
    | `isRanked` | boolean | 순위 집계 대상 여부 |
    | `rank` | integer 또는 null | 공동 순위. 미집계는 null |
    | `submissions` | object[] | 본인 대회 제출 목록 |
    | `submissions[].contestSubmissionId` | string | C 대회 접수 ID 문자열 |
    | `submissions[].problemId` | string | 문제 ID 문자열 |
    | `submissions[].receivedAt` | string (date-time) | 잠금 안에서 기록한 서버 접수 시각 |
    | `submissions[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `submissions[].verdict` | string 또는 null | 최종 판정. 미산정·공개 제한은 null |
    | `nextCursor` | string 또는 null | 다음 제출 페이지 커서 |
    | `hasMore` | boolean | 다음 제출 페이지 존재 여부 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "resultStatus": "PROVISIONAL",
        "resultRevision": "5",
        "solvedCount": 3,
        "penaltyMinutes": 142,
        "isRanked": true,
        "rank": 1,
        "content": [
          {
            "contestSubmissionId": "9001",
            "problemId": "601",
            "receivedAt": "2026-10-31T20:30:00.123456+09:00",
            "status": "JUDGED",
            "verdict": "AC"
          }
        ],
        "page": 0,
        "size": 20,
        "totalElements": 8,
        "totalPages": 1,
        "hasNext": false
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403)

#### 4.4.10 대회 이미지

- **Method / URI:** `GET /api/v1/contests/{contestId}/problems/{problemId}/images/{imageId}`
- **인증:** 로그인 필요 + 4.4.6의 열람 조건과 동일
- **대응 테이블:** `contests`, `contest_problems`, `contest_participants`
- **설명:** 고정 회차 이미지 계약은 4.2.12와 동일.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
    | `imageId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `image/*` | 원본 이미지 MIME 타입 |
    | `Cache-Control` | `private, no-store` | 응답 캐시 금지 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | 이미지 | binary | 고정 회차에 연결된 원본 이미지. JSON 래퍼 없음 |
    - 이미지 바이너리 (`Content-Type: image/*`, `Cache-Control: private, no-store`)
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_NOT_STARTED`(403)
- **외부 의존:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}` (problem)

#### 4.4.11 대회 Run

- **Method / URI:** `POST /api/v1/contests/{contestId}/problems/{problemId}/runs`
- **인증:** 로그인 필요 + `ACTIVE`·`STARTED` 본인·접수 시간 안
- **대응 테이블:** `contests`, `contest_problems`, `contest_participants`, `member_replicas`
- **설명:** 4.2.13 시험 Run과 동일 계약. 응답 본문 구조 동일.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `language` | string | Y | JAVA / PYTHON / CPP |
    | `sourceCode` | string | Y | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `customTests` | object[] | N | 선택 사용자 입력. 최대 10개 |
    | `customTests[].args` | JSON value[] | Y | 사용자 입력 인수 배열 |
    - 4.2.13과 같음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):** 4.2.13 응답 구조와 같음
- **에러:** `CONTEST_NOT_FOUND`(404), `CONTEST_NOT_REGISTERED`(403), `CONTEST_NOT_STARTED`(403), `CONTEST_CLOSED`(409), `RUN_IN_PROGRESS`(429), `RUN_TIMEOUT`(504), `CODE_TOO_LARGE`(413), `UNSUPPORTED_LANGUAGE`(400)

### 4.5 외부 API — 대회 실시간 순위

#### 4.5.1 대회 순위 SSE

- **Method / URI:** `GET /api/v1/contests/{contestId}/events`
- **인증:** 로그인 필요
- **대응 테이블:** `contests`, `contest_participants`, `contest_problem_results`, `result_snapshots`
- **설명:** 재연결 시 `Last-Event-ID`로 복구. heartbeat 30초, 60초마다 정상 종료 후 재연결. 종료·잠정·건별 정상 채점 완료 알림은 보내지 않음.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
    | `Last-Event-ID` | N | `CONTEST:501:3` | 재연결 시 마지막 수신 결과 버전 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `text/event-stream` | 응답 본문 형식 |
    | `Cache-Control` | `private, no-store` | 응답 캐시 금지 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `id` | string | 문맥:ID:resultRevision. 상태 이벤트는 본문 상태로 복구 |
    | `event` | string | 해당 문맥의 상태·결과 변경 이벤트 |
    | `data` | object | 아래 이벤트별 필드를 포함한 JSON 객체 |
- **응답 이벤트 필드:**
    
    
    | 이벤트 | 필드 | 타입 | 설명 |
    | --- | --- | --- | --- |
    | contest-rank-updated / contest-state-updated | `contestId` | string | 대회 ID |
    | contest-rank-updated | `resultStatus` | string | PROVISIONAL / FINALIZED |
    | contest-rank-updated | `resultRevision` | string | DB 결과 버전 |
    | contest-rank-updated | `updatedAt` | string (date-time) | 결과 갱신 시각 |
    | contest-state-updated | `status` | string | 대회 상태 |
    | contest-state-updated | `serverTime` | string (date-time) | C 서버 시각 |
    
    ```
    event: contest-state-updated
    id: CONTEST:8:0
    data: {"contestId":"8","status":"RUNNING","serverNow":"2026-10-31T20:00:00+09:00"}
    
    event: contest-rank-updated
    id: CONTEST:8:5
    data: {"contestId":"8","resultStatus":"PROVISIONAL","resultRevision":"5","updatedAt":"2026-10-31T20:30:05+09:00"}
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404)

### 4.6 관리자 API

#### 4.6.1 대회 생성

- **Method / URI:** `POST /api/v1/admin/contests`
- **인증:** `ADMIN`
- **대응 테이블:** `contests`, `contest_problems`, `contest_resource_guards`, `audit_logs`, `outbox_events`
- **설명:** 공개 대회를 만들고 문제(`EXAM_ONLY` 또는 `GENERAL`·`PUBLIC`)·라벨·표시 순서를 설정. B의 현재 회차를 고정. `title` 1~200자, 1~20문제, 기간 1~180분, `startsAt`은 미래, 중복 `problemId`·`label` 불가, `label`은 `A`~`T`, `displayOrder`는 1부터 연속.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:** 없음
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `title` | string | Y | 제목 |
    | `startsAt` | string (date-time) | Y | 시작 시각 |
    | `endsAt` | string (date-time) | Y | 전체 종료 시각 |
    | `isProblemsPublicAfterEnd` | boolean | Y | 대회 종료 후 로그인 회원의 문제 열람 허용 여부 |
    | `problems` | object[] | Y | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | Y | 문제 ID 문자열 |
    | `problems[].label` | string | Y | 문제 라벨 A~T. 대회 안에서 중복 불가 |
    | `problems[].displayOrder` | integer | Y | 1부터 문제 수까지 연속된 표시 순서 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "title": "Re:Solve 10월 오픈 대회",
      "startsAt": "2026-10-31T20:00:00+09:00",
      "endsAt": "2026-10-31T22:00:00+09:00",
      "isProblemsPublicAfterEnd": true,
      "problems": [
        { "problemId": "601", "label": "A", "displayOrder": 1 },
        { "problemId": "602", "label": "B", "displayOrder": 2 }
      ],
      "reason": "10월 오픈 대회 생성"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (201):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `contestId` | string | 대회 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `contestRevision` | string | 대회 설정 변경 버전 문자열 |
    | `version` | string | 조회한 낙관적 락 버전 문자열 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `startsAt` | string (date-time) | 시작 시각 |
    | `endsAt` | string (date-time) | 전체 종료 시각 |
    | `isProblemsPublicAfterEnd` | boolean | 대회 종료 후 로그인 회원의 문제 열람 허용 여부 |
    | `problems` | object[] | 고정 회차 문제·문제별 결과 목록. 문맥별 하위 필드 적용 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `problems[].revisionNumber` | integer | 문제의 수정 회차 번호 |
    | `problems[].label` | string | 문제 라벨 A~T. 대회 안에서 중복 불가 |
    | `problems[].displayOrder` | integer | 1부터 문제 수까지 연속된 표시 순서 |
    
    ```json
    {
      "success": true,
      "message": "대회가 생성되었습니다.",
      "data": {
        "contestId": "8",
        "status": "SCHEDULED",
        "contestRevision": "1",
        "version": "0",
        "resultRevision": "0",
        "startsAt": "2026-10-31T20:00:00+09:00",
        "endsAt": "2026-10-31T22:00:00+09:00",
        "isProblemsPublicAfterEnd": true,
        "problems": [
          { "problemId": "601", "problemRevisionId": "2001", "revisionNumber": 1, "label": "A", "displayOrder": 1 }
        ]
      }
    }
    ```
    
- **에러:** `AUTH_ACCESS_DENIED`(403), `EXAM_PROBLEM_NOT_AVAILABLE`(409), `CONTEST_DEPENDENCY_UNAVAILABLE`(503), `COMMON_INVALID_REQUEST`(400)
- **외부 의존:** `GET /internal/v1/problems/summaries?ids=` (problem)

#### 4.6.2 대회 취소

- **Method / URI:** `POST /api/v1/admin/contests/{contestId}/cancellation`
- **인증:** `ADMIN`
- **대응 테이블:** `contests`, `contest_problems`, `contest_participants`, `contest_participant_histories`, `audit_logs`, `outbox_events`
- **설명:** `SCHEDULED`에서만 `CANCELED`. 재요청 기존 취소 반환. 활성 등록자에게 취소 알림, 코드·참가 이력 보존.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `version` | string | Y | 조회한 낙관적 락 버전 문자열 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "version": "0",
      "reason": "운영 일정 변경으로 인한 취소"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `contestId` | string | 대회 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `canceledAt` | string (date-time) | 취소 시각 |
    | `contestRevision` | string | 대회 설정 변경 버전 문자열 |
    | `version` | string | 조회한 낙관적 락 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": "대회가 취소되었습니다.",
      "data": {
        "contestId": "8",
        "status": "CANCELED",
        "canceledAt": "2026-10-10T09:00:00+09:00",
        "contestRevision": "2",
        "version": "1"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `CONTEST_MODIFY_NOT_ALLOWED`(409), `EXAM_UPDATE_CONFLICT`(409)
- **발행:** `ContestCanceled`

#### 4.6.3 실패 시험 제출 재처리

- **Method / URI:** `POST /api/v1/admin/exam-submissions/{examSubmissionId}/retry`
- **인증:** `ADMIN` 또는 해당 시험 스터디의 현재 `LEADER`·`MANAGER`. C가 최종 검증.
- **대응 테이블:** `exams`, `exam_submissions`, `contest_admin_operations`, `audit_logs`
- **설명:** `EXAM`·`FAILED` 및 부모 `FINALIZED` 전만. 4.6.2(내부) `POST /internal/v1/judge/submissions/{submissionId}/retry`로 새 회차 요청. `contest_admin_operations`를 먼저 `PENDING` 저장 후 원격 호출. 이 작업이 남으면 결과 확정을 보류.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examSubmissionId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `expectedJudgeAttempt` | integer | Y | 처리 대상 종결 채점 회차 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "operationId": "retry-op-001",
      "expectedJudgeAttempt": 1,
      "reason": "채점 서버 재기동 후 재처리"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (202):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `operationId` | string | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `state` | string | 작업 또는 가드 상태. 해당 처리 규칙 적용 |
    | `examSubmissionId` | string | C 시험 접수 ID 문자열 |
    | `submissionId` | string | judge 제출 ID 문자열 |
    | `judgeAttempt` | integer | 종결 결과의 마지막 반영 회차 |
    | `targetJudgeAttempt` | integer 또는 null | 원격에서 확인한 재처리 목표 회차. 확인 전 null |
    
    ```json
    {
      "success": true,
      "message": "재처리를 요청했습니다.",
      "data": {
        "operationId": "retry-op-001",
        "state": "PENDING",
        "examSubmissionId": "4001",
        "submissionId": "2001",
        "judgeAttempt": 1,
        "targetJudgeAttempt": null
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `EXAM_SUBMISSION_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `REJUDGE_NOT_ALLOWED`(409), `IDEMPOTENCY_KEY_CONFLICT`(422)

#### 4.6.4 실패 제출 종결 처리

- **Method / URI:** `POST /api/v1/admin/contest-submissions/{context}/{receiptId}/resolution`
- **인증:** `ADMIN`
- **대응 테이블:** `exams`, `exam_participants`, `exam_submissions`, `exam_problem_results`, `contests`, `contest_participants`, `contest_submissions`, `contest_problem_results`, `contest_admin_operations`, `audit_logs`
- **설명:** `FAILED` 및 부모 `FINALIZED` 전만. 미완료 재처리 작업이 있으면 거절. 시험은 `isZeroConfirmed=true`·`score=0`, 대회는 `isVoidConfirmed=true`·`SYSTEM_ERROR` 무효 처리 (오답 패널티 제외). 참가자·문제 결과 재계산·`resultRevision` 증가·감사 로그를 함께 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `context` | string | Y | — | Path · EXAM / CONTEST |
    | `receiptId` | string | Y | — | Path · C 시험·대회 접수 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `action` | string | Y | 문맥 검증·관리 조치 종류. 해당 허용 값 적용 |
    | `expectedJudgeAttempt` | integer | Y | 처리 대상 종결 채점 회차 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "operationId": "resolution-op-001",
      "action": "ZERO_CONFIRM",
      "expectedJudgeAttempt": 1,
      "reason": "채점 서버 장애 복구 불가, 0점 확정"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `operationId` | string | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `context` | string | EXAM / CONTEST |
    | `receiptId` | string | C 시험·대회 접수 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `resolved` | boolean | 실패 제출의 수동 종결 여부 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    
    ```json
    {
      "success": true,
      "message": "종결 처리가 완료되었습니다.",
      "data": {
        "operationId": "resolution-op-001",
        "context": "EXAM",
        "receiptId": "4001",
        "status": "FAILED",
        "resolved": true,
        "resultRevision": "4"
      }
    }
    ```
    
- **비고:**
    - **처리:** FAILED 및 부모 FINALIZED 전만. 시험은 isZeroConfirmed=true·score=0, 대회는 isVoidConfirmed=true·SYSTEM_ERROR 무효 처리로 오답 패널티 제외. 미완료 재처리 작업이 있으면 거절한다.
    - 참가자·문제 결과 재계산·resultRevision 증가·작업 기록·감사 로그를 함께 저장한다.
    - **응답 값:** `status=FAILED`
    - **조치 종류:** EXAM의 action은 ZERO_CONFIRM, CONTEST의 action은 VOID_CONFIRM이다.
- **오류:** 공통 오류 및 §4.7. 권한 오류 403, 대상 없음 404, 상태·회차 충돌 409, 같은 작업 ID의 다른 본문 422.

#### 4.6.5 시험 결과 재확정

- **Method / URI:** `POST /api/v1/admin/exams/{examId}/results/refinalization`
- **인증:** `ADMIN`
- **대응 테이블:** `exams`, `exam_participants`, `exam_submissions`, `exam_problem_results`, `result_snapshots`, `contest_admin_operations`, `audit_logs`, `outbox_events`
- **설명:** `FINALIZED`·미종결/미완료 관리자 작업 0건만. 기존 유효 제출을 DB 기준으로 재계산, `resultRevision` 증가·새 불변 스냅샷·`ExamResultFinalized` Outbox·감사 로그. 기존 스냅샷 보존.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `examId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `expectedResultRevision` | string | Y | 재확정 대상의 이전 결과 버전 문자열 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "operationId": "refinalize-op-001",
      "expectedResultRevision": "3",
      "reason": "재채점 결과 반영 후 재확정"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `examId` | string | 시험 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `finalizedAt` | string (date-time) | 해당 스냅샷 확정 시각 |
    
    ```json
    {
      "success": true,
      "message": "결과를 재확정했습니다.",
      "data": {
        "examId": "55",
        "status": "FINALIZED",
        "resultRevision": "4",
        "finalizedAt": "2026-10-21T10:00:00+09:00"
      }
    }
    ```
    
- **에러:** `EXAM_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `RESULT_NOT_FINALIZED`(409), `RESULT_FINALIZATION_BLOCKED`(409), `IDEMPOTENCY_KEY_CONFLICT`(422)
- **발행:** `ExamResultFinalized`

#### 4.6.6 대회 결과 재확정

- **Method / URI:** `POST /api/v1/admin/contests/{contestId}/results/refinalization`
- **인증:** `ADMIN`
- **대응 테이블:** `contests`, `contest_participants`, `contest_submissions`, `contest_problem_results`, `result_snapshots`, `contest_admin_operations`, `audit_logs`, `outbox_events`
- **설명:** 4.6.5의 조건을 대회에 적용. ICPC 접수 순서로 재계산. `resultRevision` 증가·새 스냅샷·`ContestResultFinalized` Outbox·관리자 작업·감사 로그를 한 트랜잭션에 저장.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `contestId` | string | Y | — | Path · 식별자 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `expectedResultRevision` | string | Y | 재확정 대상의 이전 결과 버전 문자열 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
    
    ```json
    {
      "operationId": "refinalize-contest-op-001",
      "expectedResultRevision": "5",
      "reason": "재채점 결과 반영 후 재확정"
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `contestId` | string | 대회 ID 문자열 |
    | `status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `finalizedAt` | string (date-time) | 해당 스냅샷 확정 시각 |
    
    ```json
    {
      "success": true,
      "message": "결과를 재확정했습니다.",
      "data": {
        "contestId": "8",
        "status": "FINALIZED",
        "resultRevision": "6",
        "finalizedAt": "2026-11-01T10:00:00+09:00"
      }
    }
    ```
    
- **에러:** `CONTEST_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403), `RESULT_NOT_FINALIZED`(409), `RESULT_FINALIZATION_BLOCKED`(409), `IDEMPOTENCY_KEY_CONFLICT`(422)
- **발행:** `ContestResultFinalized`

#### 4.6.7 관리자 작업 상태 조회

- **Method / URI:** `GET /api/v1/admin/contest-operations/{operationId}`
- **인증:** `ADMIN` 또는 본인이 요청한 `EXAM_RETRY` 작업의 현재 시험 운영자. C가 작업 소유자·시험·D 구성원 권한 재검증.
- **대응 테이블:** `contest_admin_operations`
- **설명:** 관리자 작업 상태 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <accessToken>` | Gateway 인증 토큰 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `operationId` | string | Y | — | Path · 호출자 생성 멱등 작업 ID. 1~100자 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `operationId` | string | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `operationType` | string | 작업 종류. 해당 API의 허용 작업만 지정 |
    | `context` | string | EXAM / CONTEST |
    | `contextId` | string | 시험·대회 ID 문자열 |
    | `receiptId` | string | C 시험·대회 접수 ID 문자열 |
    | `state` | string | 작업 또는 가드 상태. 해당 처리 규칙 적용 |
    | `expectedJudgeAttempt` | integer | 처리 대상 종결 채점 회차 |
    | `targetJudgeAttempt` | integer 또는 null | 원격에서 확인한 재처리 목표 회차. 확인 전 null |
    | `lastErrorCode` | string 또는 null | 마지막 작업 오류 코드. 오류가 없으면 null |
    | `updatedAt` | string (date-time) | 해당 상태의 마지막 갱신 시각 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "operationId": "retry-op-001",
        "operationType": "EXAM_RETRY",
        "context": "EXAM",
        "contextId": "55",
        "receiptId": "4001",
        "state": "SUCCEEDED",
        "expectedJudgeAttempt": 1,
        "targetJudgeAttempt": 2,
        "lastErrorCode": null,
        "updatedAt": "2026-10-21T09:05:00+09:00"
      }
    }
    ```
    
- **에러:** `AUTH_ACCESS_DENIED`(403), `EXAM_NOT_FOUND`(404)

### 4.7 내부 API — C 제공

> 공통 규칙: 서비스 계정 Bearer 토큰·호출 서비스 allowlist·내부 네트워크. `/internal/**`는 Gateway 공개 라우팅에서 제외. 조회 타임아웃 2초. 검증 조회 실패는 fail-closed.
> 

#### 4.7.1 실행 문맥·열람 권한 검증

- **Method / URI:** `POST /internal/v1/contest/contexts/validate`
- **인증:** 서비스 계정 (judge, problem, study)
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`, `exam_submissions`, `contests`, `contest_problems`, `contest_participants`, `contest_submissions`
- **설명:** 고정 문제·회차 일치 검증. `READ_BODY`/`READ_IMAGE`는 종료 후 복기와 대회 종료 공개 설정 적용. `RUN`은 입장한 `ACTIVE` 참가자의 시간 안 요청만. 본인 제출 열람은 소유권·실제 입장 추가 검증.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `context` | string | Y | EXAM / CONTEST |
    | `contextId` | string | Y | 시험·대회 ID 문자열 |
    | `memberId` | string | Y | 회원 ID 문자열 |
    | `problemId` | string | Y | 문제 ID 문자열 |
    | `problemRevisionId` | string | Y | 고정 문제 수정 회차 ID 문자열 |
    | `action` | string | Y | READ_BODY / READ_IMAGE / RUN / READ_SUBMISSION |
    | `receiptId` | string | N | C 접수 ID. READ_SUBMISSION이면 필수, 다른 행위는 생략 |
    
    ```json
    {
      "context": "EXAM",
      "contextId": "55",
      "memberId": "7",
      "problemId": "101",
      "problemRevisionId": "1001",
      "action": "RUN",
      "receiptId": null
    }
    ```
    
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `allowed` | boolean | 문맥·행위 권한 검증 성공 |
    | `participantId` | string 또는 null | 검증한 본인 참가 ID. 대회 종료 공개 일반 열람은 null |
    | `problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `resultVisibility` | string | SAMPLES_ONLY / PROVISIONAL / FINALIZED / FULL_VERDICT. §4.5.1 공개 단계 적용 |
    | `personalEndsAt` | string (date-time) 또는 null | EXAM 개인 마감 / CONTEST 전체 마감. 종료 공개 일반 열람은 null |
    | `serverTime` | string (date-time) | C 서버 기준 시각 |
    
    ```json
    {
      "success": true,
      "message": null,
      "data": {
        "allowed": true,
        "participantId": "411",
        "problemRevisionId": "1001",
        "resultVisibility": "SAMPLES_ONLY",
        "personalEndsAt": "2026-10-20T21:30:00+09:00",
        "serverNow": "2026-10-20T20:15:00+09:00"
      }
    }
    ```
    
- **에러:** `CONTEXT_ACCESS_DENIED`(403), `CONTEXT_NOT_FOUND`(404), `PROBLEM_REVISION_NOT_INCLUDED`(404), `EXAM_CLOSED`(409), `CONTEST_CLOSED`(409)

#### 4.7.2 실제 입장·미확정 문맥 조회

- **Method / URI:** `GET /internal/v1/contest/members/{memberId}/active-participations`
- **인증:** member-service.
- **대응 테이블:** `exams`, `exam_participants`, `contests`, `contest_participants`
- **설명:** 실제 입장·미확정 문맥 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `memberId` | string | Y | — | Path · 회원 ID 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `withdrawalBlocked` | boolean | 실제 입장한 미확정 문맥으로 탈퇴가 차단되는지 |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].context` | string | EXAM / CONTEST |
    | `items[].contextId` | string | 시험·대회 ID 문자열 |
    | `items[].participantId` | string | 참가 ID 문자열 |
    | `items[].enteredAt` | string (date-time) 또는 null | 최초 실제 입장 시각. 미입장은 null |
    | `items[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
- **비고:**
    - **기준:** enteredAt이 있고 부모 상태가 FINALIZED/CANCELED가 아닌 참가는 포함한다.
    - 단순 등록·ABSENT는 포함하지 않는다.
    - 조회 결과만으로 탈퇴를 확정하지 않고 4.5.3 가드를 준비한다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.3 자원 작업 준비

- **Method / URI:** `POST /internal/v1/contest/guards/{resourceType}/{resourceId}/prepare`
- **인증:** MEMBER/WITHDRAWAL은 member-service, STUDY/STUDY_CLOSE는 study-service, PROBLEM/PROBLEM_HIDE는 problem-service만.
- **대응 테이블:** `contest_resource_guards`, `exams`, `exam_participants`, `contests`, `contest_participants`, `exam_problems`, `contest_problems`, `outbox_events`
- **설명:** 자원 작업 준비
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `resourceType` | string | Y | — | Path · MEMBER / STUDY / PROBLEM |
    | `resourceId` | string | Y | — | Path · 소유 서비스 자원 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `operationType` | string | Y | 작업 종류. 해당 API의 허용 작업만 지정 |
    | `expectedSourceVersion` | string | Y | 원천 상태의 기대 순서 값 문자열 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200 / 202):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `operationId` | string | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `state` | string | 작업 또는 가드 상태. 해당 처리 규칙 적용 |
    | `ready` | boolean | 소유 서비스가 원격 작업을 완료할 수 있는지 |
    | `preparedAt` | string (date-time) | 자원 가드 준비 시각 |
    | `canceledExamIds` | string[] | 준비 과정에서 취소한 예정 시험 ID 목록 |
    | `blockingExamIds` | string[] | 종료를 기다리는 시험 ID 목록 |
- **비고:**
    - **MEMBER:** 자원 잠금 아래 실제 입장한 미확정 시험·대회가 있으면 WITHDRAWAL_BLOCKED_ACTIVE_EXAM(409). 없으면 PREPARED 저장 후 새 등록·입장을 차단한다.
    - **STUDY:** PREPARED와 신규 시험 생성 차단을 저장한다.
    - 모든 SCHEDULED 시험을 동일 취소 계약으로 취소하고 등록자에게 알린다.
    - 아직 시간상 진행 중인 시험이 있으면 202·ready=false·blockingExamIds 반환; 재요청 시 종료를 확인한다.
    - 모든 시험이 CLOSED/FINALIZED/CANCELED면 ready=true. 아직 미확정인 CLOSED 시험은 차단하지 않는다.
    - **PROBLEM:** SCHEDULED 또는 시간상 진행 중인 시험·대회에 포함되면 PROBLEM_IN_ACTIVE_EXAM(409). 없으면 PREPARED 저장 후 새 문제 편입을 막는다.
    - EXAM_ONLY 종료공개와 고정 회차 채점은 가드와 분리한다.
    - **응답 조건:** 진행 중 스터디 시험만 202/ready=false. 회원·문제 거절은 준비 상태를 남기지 않는다.
    - **응답 값:** `state=PREPARED`
    - **멱등:** 같은 operationId·type·expectedSourceVersion이면 기존 상태 반환. prepare 요청 해시는 DB 가드에 저장하고 완료·취소 후에도 마지막 operationId와 함께 보존한다.
    - 취소된 같은 작업은 OPEN을 반환하며 재준비하지 않는다.
    - 다른 본문은 422 IDEMPOTENCY_KEY_CONFLICT. 다른 작업이 점유하면 409 CONTEST_OPERATION_BLOCKED. expectedSourceVersion < 저장 sourceVersion은 409 SOURCE_VERSION_CONFLICT.
    - **작업 유형:** MEMBER/WITHDRAWAL은 member-service, STUDY/STUDY_CLOSE는 study-service, PROBLEM/PROBLEM_HIDE는 problem-service만 호출한다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.4 자원 작업 완료·취소

- **Method / URI:** `POST /internal/v1/contest/guards/{resourceType}/{resourceId}/complete`, `POST /internal/v1/contest/guards/{resourceType}/{resourceId}/cancel`
- **인증:** §4.5.3의 자원 소유 서비스.
- **대응 테이블:** `contest_resource_guards`
- **설명:** 자원 작업 완료·취소
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `resourceType` | string | Y | — | Path · MEMBER / STUDY / PROBLEM |
    | `resourceId` | string | Y | — | Path · 소유 서비스 자원 ID 문자열 |
- **요청 본문:**
    
    
    | 필드 | 타입 | 필수 | 설명 |
    | --- | --- | --- | --- |
    | `operationId` | string | Y | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `sourceVersion` | string | Y | 소유 서비스의 상태 순서 값 문자열 |
    | `reason` | string | Y | 조치 사유. 1~500자 |
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `operationId` | string | 호출자 생성 멱등 작업 ID. 1~100자 |
    | `state` | string | 작업 또는 가드 상태. 해당 처리 규칙 적용 |
    | `sourceVersion` | string | 원천 상태 순서 값 문자열 |
- **비고:**
    - **complete:** 소유 서비스가 자기 DB 상태를 변경한 뒤 호출한다.
    - 가드 operationId 일치와 sourceVersion 증가를 검증해 BLOCKED로 전이한다.
    - STUDY는 ready=true일 때만 완료한다.
    - MemberWithdrawn/StudyClosed/ProblemStateChanged도 같은 완료 처리를 복구한다.
    - **cancel:** 소유 서비스가 작업을 실행하지 않았음을 확정한 뒤 OPEN으로 전이한다.
    - 이미 완료된 작업은 취소 불가(409 OPERATION_ALREADY_COMPLETED). 스터디 준비로 취소된 시험을 자동 복원하지 않는다.
    - **응답 조건:** 반복 완료·취소는 같은 결과 반환. 오래된 작업 ID는 409 OPERATION_NOT_CURRENT.
    - **복구:** C는 PREPARED 1분 초과 건을 5분 주기로 소유 서비스 operation 상태 API로 대사한다.
    - COMMITTED는 complete, ABORTED는 cancel, PENDING/조회 실패는 유지·운영 알림. TTL 자동 해제 없음. PROBLEM 재공개는 sourceVersion이 더 큰 PUBLIC 이벤트에만 OPEN.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.5 스터디 시험 상태 조회

- **Method / URI:** `GET /internal/v1/contest/studies/{studyId}/exams`
- **인증:** study-service.
- **대응 테이블:** `exams`
- **설명:** 스터디 시험 상태 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `studyId` | string | Y | — | Path · 식별자 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].examId` | string | 시험 ID 문자열 |
    | `items[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `items[].startsAt` | string (date-time) | 시작 시각 |
    | `items[].endsAt` | string (date-time) | 전체 종료 시각 |
    | `items[].examRevision` | string | 시험 설정 변경 버전 문자열 |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
- **비고:**
    - **응답 조건:** CLOSED와 FINALIZED를 구분한다.
    - 종료 가능 여부는 4.5.3의 ready로 확정한다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.6 고정 문제 사용 조회

- **Method / URI:** `GET /internal/v1/contest/problems/{problemId}/usages`
- **인증:** problem-service.
- **대응 테이블:** `exams`, `exam_problems`, `contests`, `contest_problems`
- **설명:** 고정 문제 사용 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `problemId` | string | Y | — | Path · 문제 ID 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].context` | string | EXAM / CONTEST |
    | `items[].contextId` | string | 시험·대회 ID 문자열 |
    | `items[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `items[].status` | string | 해당 참가·접수·문맥의 상태. 본문의 공개·전이 조건 적용 |
    | `items[].startsAt` | string (date-time) | 시작 시각 |
    | `items[].endsAt` | string (date-time) | 전체 종료 시각 |
    | `hasBlockingUsage` | boolean | 예정·진행 중 문제 사용이 존재하는지 |
- **비고:**
    - **기준:** 예정·진행 중 사용을 반환한다.
    - 조회와 비공개 전환 사이 경합은 4.5.3으로 막는다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.7 힌트 차단 복구 조회

- **Method / URI:** `GET /internal/v1/contest/hint-blocks`
- **인증:** study-service.
- **대응 테이블:** `exams`, `exam_problems`, `exam_participants`, `contests`, `contest_problems`, `contest_participants`
- **설명:** 힌트 차단 복구 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `memberId` | string | Y | — | Query · 회원 ID 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].context` | string | EXAM / CONTEST |
    | `items[].contextId` | string | 시험·대회 ID 문자열 |
    | `items[].participantId` | string | 참가 ID 문자열 |
    | `items[].memberId` | string | 회원 ID 문자열 |
    | `items[].participantRevision` | string | 참가 상태 변경 버전 문자열 |
    | `items[].contextRevision` | string | examRevision / contestRevision 문자열 |
    | `items[].problemIds` | string[] | 해당 문맥에 고정 편입된 문제 ID 목록 |
    | `items[].startsAt` | string (date-time) | 시작 시각 |
    | `items[].endsAt` | string (date-time) | 전체 종료 시각 |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
- **비고:**
    - **대상:** 활성 등록자 중 CANCELED가 아닌 참가, 시험/대회가 CANCELED/CLOSED/ENDED/FINALIZED가 아닌 문맥. STARTED와 REGISTERED 모두 포함한다.
    - 개인 수동 종료는 전체 힌트 차단을 해제하지 않는다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.8 제출 공유 스냅샷 조회

- **Method / URI:** `GET /internal/v1/contest/submissions/{context}/{receiptId}/shareable`
- **인증:** problem-service. 공유 요청 회원 본인 검증.
- **대응 테이블:** `exams`, `exam_submissions`, `contests`, `contest_submissions`
- **설명:** 제출 공유 스냅샷 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `context` | string | Y | — | Path · EXAM / CONTEST |
    | `receiptId` | string | Y | — | Path · C 시험·대회 접수 ID 문자열 |
    | `memberId` | string | Y | — | Query · 회원 ID 문자열 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
    | `Cache-Control` | `private, no-store` | 응답 캐시 금지 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `context` | string | EXAM / CONTEST |
    | `contextId` | string | 시험·대회 ID 문자열 |
    | `receiptId` | string | C 시험·대회 접수 ID 문자열 |
    | `submissionId` | string | judge 제출 ID 문자열 |
    | `memberId` | string | 회원 ID 문자열 |
    | `problemId` | string | 문제 ID 문자열 |
    | `problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `language` | string | JAVA / PYTHON / CPP |
    | `sourceCode` | string | UTF-8 코드. 공통 코드 크기·빈 코드 규칙 적용 |
    | `verdict` | string 또는 null | 최종 판정. 미산정·공개 제한은 null |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
- **비고:**
    - **검증:** receiptId는 examSubmissionId/contestSubmissionId, 본인 제출·AC·부모 FINALIZED. 틀리면 SUBMISSION_NOT_SHAREABLE(409), 다른 회원은 403. C의 불변 코드와 고정 회차를 반환한다.
    - **응답 값:** `verdict=AC`
    - **보안:** 호출자는 공유 스터디 권한을 별도로 검증하고 코드 스냅샷을 자기 DB에 저장한다.
    - 타인 코드·숨김 테스트는 반환하지 않는다.
    - Cache-Control: no-store.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

#### 4.7.9 확정 결과 스냅샷 조회

- **Method / URI:** `GET /internal/v1/contest/results/{context}/{contextId}`
- **인증:** 분석 서비스(P3), study-service.
- **대응 테이블:** `result_snapshots`
- **설명:** 확정 결과 스냅샷 조회
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Authorization` | Y | `Bearer <serviceToken>` | 서비스 계정·호출 allowlist 검증 |
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `context` | string | Y | — | Path · EXAM / CONTEST |
    | `contextId` | string | Y | — | Path · 시험·대회 ID 문자열 |
    | `resultRevision` | string | N | — | Query · 결과 갱신·확정 버전 문자열 |
    | `after` | string | N | — | Query · 불투명 페이지 커서. 생략 시 첫 페이지 |
    | `size` | integer | N | 20 | Query · 1~100. 기본 20 |
- **요청 본문:** 없음
- **응답 헤더:**
    
    
    | 헤더 | 값 | 설명 |
    | --- | --- | --- |
    | `Content-Type` | `application/json` | 응답 본문 형식 |
- **응답 본문 (200):**
    
    
    | 필드 | 타입 | 설명 |
    | --- | --- | --- |
    | `context` | string | EXAM / CONTEST |
    | `contextId` | string | 시험·대회 ID 문자열 |
    | `resultStatus` | string | FINALIZED |
    | `resultRevision` | string | 결과 갱신·확정 버전 문자열 |
    | `finalizedAt` | string (date-time) | 해당 스냅샷 확정 시각 |
    | `problems` | object[] | 고정 문제 목록. 참가자 결과는 items[].problems에 포함 |
    | `problems[].problemId` | string | 문제 ID 문자열 |
    | `problems[].problemRevisionId` | string | 고정 문제 수정 회차 ID 문자열 |
    | `problems[].score` | string 또는 null | EXAM 배점 문자열. CONTEST는 null |
    | `items` | object[] | 조회 항목 목록. 하위 행의 필드 적용 |
    | `items[].memberId` | string | 회원 ID 문자열 |
    | `items[].participantId` | string | 참가 ID 문자열 |
    | `items[].isRanked` | boolean | 순위 집계 대상 여부 |
    | `items[].rank` | integer 또는 null | 공동 순위. 미집계는 null |
    | `items[].score` | string 또는 null | EXAM 확정 총점 문자열. CONTEST는 null |
    | `items[].solvedCount` | integer 또는 null | CONTEST 해결 문제 수. EXAM은 null |
    | `items[].penaltyMinutes` | integer 또는 null | CONTEST 패널티 분. EXAM은 null |
    | `items[].problems` | object[] | 해당 참가자의 확정 문제별 결과. 미제출 문제도 포함 |
    | `items[].problems[].problemId` | string | 고정 문제 ID |
    | `items[].problems[].verdict` | string 또는 null | EXAM 마지막 유효 제출 판정. 미제출·0점 수동 종결은 null |
    | `items[].problems[].score` | string 또는 null | EXAM 확정 문제 점수. CONTEST는 null |
    | `items[].problems[].passedCount` | integer 또는 null | EXAM 정상 채점의 통과 수. 미제출·수동 종결·CONTEST는 null |
    | `items[].problems[].totalCount` | integer 또는 null | EXAM 정상 채점의 테스트 수. 미제출·수동 종결·CONTEST는 null |
    | `items[].problems[].isSolved` | boolean 또는 null | CONTEST 최초 AC 존재 여부. EXAM은 null |
    | `items[].problems[].wrongCount` | integer 또는 null | CONTEST 최초 AC 이전 유효 오답 수. EXAM은 null |
    | `items[].problems[].acceptedAt` | string (date-time) 또는 null | CONTEST 최초 AC 접수 시각. EXAM·미해결은 null |
    | `nextCursor` | string 또는 null | 다음 페이지 커서. 마지막 페이지는 null |
    | `hasMore` | boolean | 다음 페이지 존재 여부 |
- **비고:**
    - **응답 값:** `resultStatus=FINALIZED`
    - **기준:** result_snapshots의 같은 버전을 페이지 전체에서 사용한다.
    - 미확정은 RESULT_NOT_FINALIZED(409), 없는 버전은 RESULT_REVISION_NOT_FOUND(404). 코드·이메일·숨김 케이스 없음. PRACTICE 승급·진행률 집계에 사용하지 않는다.
- **오류:** 공통 오류 및 §4.7의 해당 조건을 적용한다.

### 4.8 내부 API — C가 사용하는 외부 서비스

> 공통 규칙: contest-service 서비스 계정. 조회 타임아웃 2초. 검증 실패는 503 `CONTEST_DEPENDENCY_UNAVAILABLE`. 
재시도: 연결 실패·502/503/504만 최대 2회, 100ms·300ms 지수 백오프.
> 

#### 4.8.1 문제 선택 (problem)

- **Method / URI:** `GET /internal/v1/problems/summaries?ids={id1,id2,...}`
- **응답 필드:** `problemId`, `scope`, `visibility`, `problemRevisionId`, `revisionNumber`, `title`
- **비고:** 누락 ID는 `EXAM_PROBLEM_NOT_AVAILABLE`(409). 시험은 `GENERAL`/`PUBLIC`, 대회는 `EXAM_ONLY`/`PRIVATE` 또는 `GENERAL`/`PUBLIC`만 편입. `ARCHIVED`는 신규 편입 불가.

#### 4.8.2 고정 회차 본문 (problem)

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content?context=&contextId=&memberId=&action=READ_BODY`
- **비고:** B가 `action=READ_BODY`로 §4.7.1 열람 권한을 검증. 조회 실패 시 503.

#### 4.8.3 고정 회차 이미지 (problem)

- **Method / URI:** `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/images/{imageId}?context=&contextId=&memberId=&action=READ_IMAGE`
- **비고:** `action=READ_IMAGE`로 고정 회차 연결 검증. 이미지 없음·열람 불가는 404.

#### 4.8.4 채점 상태 대사 (judge)

- **Method / URI:** `GET /internal/v1/judge/submissions/by-receipt?context=&receiptId=`
- **비고:** `found=true`이면 `context`·`contextId`·`receiptId`·`userId`·`problemId`·`problemRevisionId`·`submittedAt` 대조. 미종결·실패의 `verdict`/`passedCount`/`totalCount`는 `null`.

#### 4.8.5 시험 실패 재처리 (judge)

- **Method / URI:** `POST /internal/v1/judge/submissions/{submissionId}/retry`
- **요청 필드:** `context`(`EXAM`만), `receiptId`, `expectedJudgeAttempt`, `operationId`, `actorMemberId`, `reason`
- **응답:** `submissionId`, `judgeAttempt`(목표 회차 = `expectedJudgeAttempt + 1`), `status`(`QUEUED`)

#### 4.8.6 코드 실행 (judge)

- **Method / URI:** `POST /internal/v1/judge/runs`
- **요청 필드:** `context`, `contextId`, `memberId`, `problemId`, `problemRevisionId`, `language`, `sourceCode`, `customTests[].args`
- **비고:** §4.7.1 `RUN` 검증 후 공개 예제·사용자 입력만 동기 실행 (30초). 결과 저장·이벤트 발행 없음.

#### 4.8.7 스터디 구성원 조회 (study)

- **Method / URI:** `GET /internal/v1/studies/{studyId}/members`
- **비고:** 시험 생성·수정 시 `LEADER`·`MANAGER` 역할 검증. `studyStatus = CLOSED`이면 시험 생성·수정 불가.

#### 4.8.8 회원 상태 조회 (member)

- **Method / URI:** `GET /internal/v1/members/{memberId}/status`
- **비고:** 신규 등록·입장·Draft·제출·Run 전 `ACTIVE` 확인. 이미 접수한 동일 요청 반환·자동 제출·채점 반영·읽기는 소급 취소 안 함.

#### 4.8.9 외부 작업 상태 대사 (member·study·problem)

- **Method / URI:** `GET /internal/v1/members/{memberId}/operations/{operationId}`, `GET /internal/v1/studies/{studyId}/operations/{operationId}`, `GET /internal/v1/problems/{problemId}/operations/{operationId}`
- **응답 필드:** `operationId`, `state`(`PENDING`/`COMMITTED`/`ABORTED`), `sourceVersion`, `resourceStatus`
- **비고:** `COMMITTED` 확인 후 4.7.4 complete, `ABORTED` 확인 후 cancel로 복구. `PENDING`·404·조회 실패는 차단 유지. 없는 작업 404를 자동 취소 근거로 사용하지 않음.

---

## 5. 스터디 (study)

> 작성: 담당 D · 서비스: `study-service`
> 

### 5.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/studies/**`, `/api/v1/admin/studies/**` |
| 내부 경로 | `/internal/v1/studies/**` |
| 소유 테이블 | 스키마 `study` |
| 동시성 원칙 | 정원·참여 상한은 카운터 조건부 UPDATE, "진행 중 1건"은 생성 컬럼 + 유일 제약, 
상태 전이는 `WHERE status = :expected` 조건부 UPDATE |
| 이벤트 발행 | 모든 발행은 Outbox(같은 트랜잭션) |

### 5.1 외부 API — 스터디 탐색·조회

#### 5.1.1 스터디 목록 조회

- **Method / URI:** `GET /api/v1/studies`
- **인증:** 공개
- **대응 테이블:** `studies`
- **설명:** 모집 중(`RECRUITING`) 스터디를 조건으로 검색한다. 학습 프로필 없이도 조회할 수 있다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `keyword` | string | N | — | 이름·설명 검색 |
    | `goal` | string | N | — | 목표 필터 |
    | `skillLevel` | string | N | — | 대상 레벨 필터 (태그 레벨 집계 방식 확정 시 조정) |
    | `preferredLanguage` | string | N | — | 선호 언어 필터 (공통 Enum) |
    | `activeTimeSlot` | string | N | — | 활동 시간대 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 | 최대 100 |
    
    허용 정렬 필드: `createdAt`, `memberCount`, `lastActivityAt` (기본 `createdAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            { "studyId": "12", "name": "코딩테스트 스터디", "goal": "EMPLOYMENT", "skillLevel": "LV2", "preferredLanguage": "java", "activeTimeSlot": "EVENING", "capacity": 10, "memberCount": 5, "status": "RECRUITING", "joinType": "APPROVAL", "lastActivityAt": "2026-09-27T21:10:00+09:00", "createdAt": "2026-09-21T10:00:00+09:00" }
          ],
          "page": 0, "size": 20, "totalElements": 42, "totalPages": 3, "hasNext": true
        }
      }
    ```
    
    - `goal`·`skillLevel`·`activeTimeSlot` 값 체계는 학습 프로필과 같은 공통 Enum을 쓴다.

#### 5.1.2 스터디 상세

- **Method / URI:** `GET /api/v1/studies/{studyId}`
- **인증:** 공개 (로그인 시 `myMembership` 포함)
- **대응 테이블:** `studies`, `study_memberships`, `member_replicas`
- **설명:** 비구성원도 열람할 수 있다. `CLOSED` 스터디도 조회된다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "studyId": "12", "name": "코딩테스트 스터디", "description": "취업 준비 알고리즘 스터디",
          "goal": "EMPLOYMENT", "skillLevel": "LV2", "preferredLanguage": "java", "activeTimeSlot": "EVENING",
          "capacity": 10, "memberCount": 5, "status": "RECRUITING", "joinType": "APPROVAL", "isAutoReopen": true,
          "version": 3,
          "leader": { "memberId": "1", "nickname": "leader1" },
          "myMembership": { "membershipId": "55", "status": "APPROVED", "role": "MEMBER" },
          "createdAt": "2026-09-21T10:00:00+09:00"
        }
      }
    ```
    
    - `myMembership`: 비로그인 또는 진행 중 가입(`PENDING`·`APPROVED`)이 없으면 `null`
    - `version`: 스터디 설정 수정(5.3.1)에 쓰는 낙관적 락 값
- **에러:**
    - `STUDY_NOT_FOUND`(404)

#### 5.1.3 내 스터디 목록

- **Method / URI:** `GET /api/v1/studies/me`
- **인증:** 로그인 필요
- **대응 테이블:** `study_memberships`, `studies`, `study_member_quotas`
- **설명:** 본인이 `PENDING`·`APPROVED` 상태인 스터디와 활성 참여 수를 반환한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | `PENDING` / `APPROVED` |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `joinedAt`, `createdAt` (기본 `joinedAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { "studyId": "12", "name": "코딩테스트 스터디", "studyStatus": "ACTIVE", "membershipId": "55", "membershipStatus": "APPROVED", "role": "MEMBER", "joinedAt": "2026-09-22T09:10:00+09:00" } ],
          "page": 0, "size": 20, "totalElements": 2, "totalPages": 1, "hasNext": false,
          "activeJoinCount": 2,
          "activeJoinLimit": 5
        }
      }
    ```
    

#### 5.1.4 추천 스터디

- **Method / URI:** `GET /api/v1/studies/recommendations`
- **인증:** 로그인 필요
- **대응 테이블:** `study_recommendations`, `member_learning_profile_replicas`, `studies`
- **설명:** 학습 프로필이 있으면 규칙 기반 추천(`PERSONALIZED`), 없거나 결과가 비면 최근 활동순(`POPULAR`)을 반환한다. 추천 결과가 하루보다 오래됐으면 즉시 재계산한다. 이미 속한 스터디와 정원이 찬 스터디는 제외한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "mode": "PERSONALIZED",
          "isProfileRequired": false,
          "content": [ { "studyId": "12", "name": "코딩테스트 스터디", "score": 87.00, "reasons": ["목표 일치", "태그 레벨 비슷함", "선호 언어 같음"], "memberCount": 5, "capacity": 10, "status": "RECRUITING" } ],
          "page": 0, "size": 20, "totalElements": 8, "totalPages": 1, "hasNext": false
        }
      }
    ```
    
    - `mode`: `PERSONALIZED` / `POPULAR`. `POPULAR`이면 `score`는 `null`, `reasons`는 `["최근 활동이 많은 스터디"]`
    - `isProfileRequired`: 학습 프로필이 없으면 `true` (화면에서 프로필 입력 안내). 프로필이 없어도 오류를 반환하지 않는다.

### 5.2 외부 API — 스터디 생성·가입

#### 5.2.1 스터디 생성

- **Method / URI:** `POST /api/v1/studies`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `studies`, `study_memberships`, `study_member_quotas`, `study_membership_histories`
- **설명:** 스터디를 만들고 생성자를 `LEADER`(`APPROVED`)로 등록한다. 생성자의 참여 상한 카운터를 같은 트랜잭션에서 확보한다.
- **요청 바디:**
    
    ```json
      { "name": "코딩테스트 스터디", "description": "취업 준비 알고리즘 스터디", "goal": "EMPLOYMENT", "skillLevel": "LV2", "preferredLanguage": "java", "activeTimeSlot": "EVENING", "capacity": 10, "joinType": "APPROVAL", "isAutoReopen": true }
    ```
    
    - `name` 1~200자, `description` 0~500자, `capacity` 2~10, `joinType` `INSTANT` / `APPROVAL`
- **응답 (201):**
    
    ```json
      { "success": true, "message": "스터디가 생성되었습니다.", "data": { "studyId": "12" } }
    ```
    
- **에러:**
    - `COMMON_INVALID_REQUEST`(400), `STUDY_JOIN_LIMIT_EXCEEDED`(409), `MEMBER_SUSPENDED`(403)
- **동시성/멱등 보장:**
    - 트랜잭션 순서 = ① `study_member_quotas` 조건부 UPDATE(`active_count < 5`) ② `studies` INSERT(`member_count = 1`) ③ `study_memberships` INSERT(`LEADER`, `APPROVED`) ④ 이력 INSERT. ①이 0행이면 전체 롤백.

#### 5.2.2 스터디 가입 신청·즉시 가입

- **Method / URI:** `POST /api/v1/studies/{studyId}/memberships`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **Idempotency:** 없음 (중복은 유일 제약으로 `STUDY_ALREADY_APPLIED`)
- **대응 테이블:** `studies`, `study_memberships`, `study_member_quotas`, `assignment_member_statuses`, `assignment_completions`, `study_membership_histories`, `outbox_events`
- **설명:** `APPROVAL` 스터디는 `PENDING`(만료 7일), `INSTANT` 스터디는 즉시 `APPROVED`를 만든다. 즉시 가입이면 같은 트랜잭션에서 `SCHEDULED`·`OPEN` 문제집에 편입하고, `OPEN` 문제집은 기존 풀이를 반영한다. 요청 바디는 없다.
- **응답 (201):**
    
    ```json
      { "success": true, "message": "가입 신청이 완료되었습니다.", "data": { "membershipId": "55", "status": "PENDING", "expiresAt": "2026-10-05T09:00:00+09:00" } }
    ```
    
    - `INSTANT` 스터디: `status: "APPROVED"`, `expiresAt: null`, `message: "스터디에 가입되었습니다."`
- **에러:**
    - `STUDY_NOT_FOUND`(404), `STUDY_NOT_RECRUITING`(409), `STUDY_ALREADY_APPLIED`(409), `STUDY_JOIN_LIMIT_EXCEEDED`(409), `STUDY_FULL`(409), `MEMBER_SUSPENDED`(403)
- **동시성/멱등 보장:**
    - 중복 신청: 생성 컬럼 `active_member_id` + `uk_study_memberships_study_active_member`
    - 즉시 가입 확보 순서: ① 회원 카운터 `UPDATE study_member_quotas SET active_count = active_count + 1 WHERE member_id = ? AND active_count < 5` ② 스터디 정원 `UPDATE studies SET member_count = member_count + 1, status = IF(member_count >= capacity, 'ACTIVE', status), version = version + 1 WHERE id = ? AND status = 'RECRUITING' AND member_count < capacity`. MySQL 단일 테이블 UPDATE는 SET을 왼쪽부터 평가하므로 `status` 계산은 증가된 `member_count`를 본다. 어느 쪽이든 0행이면 전체 롤백.
    - 문제집 편입: `assignment_member_statuses`·`assignment_completions` 유일 제약 upsert. 개설 스케줄러와 동시에 실행돼도 1건만 남는다.
- **발행:**
    - `StudyMemberJoined` — 즉시 가입 시

#### 5.2.3 가입 신청 취소·자진 탈퇴

- **Method / URI:** `DELETE /api/v1/studies/{studyId}/memberships/me`
- **인증:** 로그인 필요
- **대응 테이블:** `study_memberships`, `studies`, `study_member_quotas`, `study_membership_histories`, `outbox_events`
- **설명:** `PENDING`이면 `CANCELED`, `APPROVED`이면 `LEFT`로 전이한다. 스터디장은 위임 전에는 탈퇴할 수 없다. 탈퇴해도 문제집 진행 기록은 보존하되 통계에서 제외한다.
- **응답 (204)**
- **에러:**
    - `STUDY_MEMBERSHIP_NOT_FOUND`(404), `STUDY_LEADER_DELEGATION_REQUIRED`(409)
- **동시성/멱등 보장:**
    - `UPDATE study_memberships SET status = :next WHERE id = ? AND status = :current`. 0행이면 재조회해 이미 해제됐으면 `STUDY_MEMBERSHIP_NOT_FOUND`.
    - `LEFT`이면 같은 트랜잭션에서 `member_count − 1`, 회원 카운터 `− 1`. `ACTIVE`이고 `is_auto_reopen = true`이면 `RECRUITING`으로 되돌린다.
- **발행:**
    - `StudyMemberLeft` — `LEFT` 전이 시

#### 5.2.4 구성원·대기 신청 목록

- **Method / URI:** `GET /api/v1/studies/{studyId}/memberships`
- **인증:** 로그인 필요
- **대응 테이블:** `study_memberships`, `member_replicas`, `member_learning_profile_replicas`
- **설명:** `status=APPROVED`는 구성원 누구나, `status=PENDING`은 `LEADER`·`MANAGER`만 조회한다. 대기 신청에는 승인 심사용 학습 프로필 공개 항목을 포함한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | `APPROVED` | `APPROVED` / `PENDING` |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `joinedAt`, `appliedAt` (기본 `APPROVED`: `joinedAt,asc` / `PENDING`: `appliedAt,asc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [
            { "membershipId": "55", "memberId": "7", "nickname": "user7", "role": "MEMBER", "status": "PENDING",
              "learningProfile": { "goal": "EMPLOYMENT", "tagLevels": { "해시": "LV2", "그래프": "LV1" }, "preferredLanguages": ["java"] },
              "appliedAt": "2026-09-28T09:00:00+09:00", "expiresAt": "2026-10-05T09:00:00+09:00", "joinedAt": null }
          ],
          "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "hasNext": false
        }
      }
    ```
    
    - `learningProfile`: `PENDING` 조회에서만 채운다. 복제본이 없으면 `null`. 단일 `skillLevel` 대신 태그별 레벨.
- **에러:**
    - `STUDY_NOT_FOUND`(404), `AUTH_ACCESS_DENIED`(403)

#### 5.2.5 가입 신청 승인·거절·강제 탈퇴

- **Method / URI:** `PATCH /api/v1/studies/{studyId}/memberships/{membershipId}`
- **인증:** 로그인 필요 + 해당 스터디 `LEADER`·`MANAGER`
- **Idempotency:** 없음 (같은 목표 상태 재요청은 `STUDY_MEMBERSHIP_STATUS_CONFLICT`)
- **대응 테이블:** `study_memberships`, `studies`, `study_member_quotas`, `assignment_member_statuses`, `assignment_completions`, `study_membership_histories`, `outbox_events`
- **설명:** `PENDING → APPROVED`(승인), `PENDING → REJECTED`(거절), `APPROVED → REMOVED`(강제 탈퇴, 사유 필수). `MANAGER`는 `LEADER`·다른 `MANAGER`를 강제 탈퇴시킬 수 없다.
- **요청 바디:**
    
    ```json
      { "status": "APPROVED", "reason": null }
    ```
    
    - `status`: `APPROVED` / `REJECTED` / `REMOVED`. `REMOVED`는 `reason`(1~500자) 필수
- **응답 (200):**
    
    ```json
      { "success": true, "message": "승인 처리가 완료되었습니다.", "data": { "membershipId": "55", "status": "APPROVED" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_MEMBERSHIP_NOT_FOUND`(404), `STUDY_MEMBERSHIP_STATUS_CONFLICT`(409), `STUDY_APPLICATION_EXPIRED`(409), `STUDY_JOIN_LIMIT_EXCEEDED`(409), `STUDY_FULL`(409), `STUDY_ALREADY_CLOSED`(409)
- **동시성/멱등 보장:**
    - 승인: ① `UPDATE study_memberships SET status='APPROVED' WHERE id=? AND status='PENDING' AND expires_at > now()` — 0행이면 재조회해 만료면 `STUDY_APPLICATION_EXPIRED`, 아니면 `STUDY_MEMBERSHIP_STATUS_CONFLICT` ② 신청자 회원 카운터 확보 ③ 스터디 정원 확보(`status <> 'CLOSED' AND member_count < capacity`) ④ 문제집 편입(upsert). 어느 단계든 실패하면 전체 롤백.
    - 승인·만료·취소 경합: 모두 `status = 'PENDING'` 조건부 UPDATE이므로 먼저 커밋한 쪽만 반영.
- **발행:**
    - 승인 → `StudyMemberJoined` + `StudyApplicationDecided` / 거절 → `StudyApplicationDecided` / 강제 탈퇴 → `StudyMemberLeft`

### 5.3 외부 API — 스터디 운영

#### 5.3.1 스터디 설정 수정

- **Method / URI:** `PATCH /api/v1/studies/{studyId}`
- **인증:** 로그인 필요 + `LEADER`
- **대응 테이블:** `studies`
- **설명:** 바꿀 필드만 보낸다. `APPROVAL → INSTANT`는 대기 신청이 있으면 거절한다. 정원은 현재 구성원 수보다 작게 줄일 수 없다.
- **요청 바디:**
    
    ```json
      { "description": "새 설명", "joinType": "INSTANT", "capacity": 8, "isAutoReopen": false, "version": 3 }
    ```
    
    - `version`: 스터디 상세 조회(5.1.2)에서 받은 값(낙관적 락)
- **응답 (200):**
    
    ```json
      { "success": true, "message": "스터디 정보가 수정되었습니다.", "data": { "studyId": "12", "version": 4 } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_NOT_FOUND`(404), `STUDY_ALREADY_CLOSED`(409), `STUDY_PENDING_APPLICATIONS_EXIST`(409), `STUDY_CAPACITY_TOO_SMALL`(409), `STUDY_UPDATE_CONFLICT`(409)
- **동시성/멱등 보장:** `UPDATE studies SET …, version = version + 1 WHERE id = ? AND version = :version AND status <> 'CLOSED' AND member_count <= :newCapacity`. 0행이면 재조회해 원인별 코드로 응답한다. 가입·탈퇴의 카운터 UPDATE도 `version`을 올리므로 조회 후 가입이 일어나면 충돌로 감지된다.

#### 5.3.2 운영진 임명·해제

- **Method / URI:** `PATCH /api/v1/studies/{studyId}/memberships/{membershipId}/role`
- **인증:** 로그인 필요 + `LEADER`
- **Idempotency:** 같은 역할로 변경 요청은 변경 없이 200
- **대응 테이블:** `study_memberships`, `studies`, `study_membership_histories`
- **요청 바디:**
    
    ```json
      { "role": "MANAGER" }
    ```
    
    - `MANAGER` / `MEMBER`
- **응답 (200):**
    
    ```json
      { "success": true, "message": "역할이 변경되었습니다.", "data": { "membershipId": "55", "role": "MANAGER" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_MEMBERSHIP_NOT_FOUND`(404), `STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED`(409), `STUDY_MANAGER_LIMIT_EXCEEDED`(409)
- **동시성/멱등 보장:** `studies.manager_count` 조건부 UPDATE(`manager_count < 2`)로 확보한 뒤 역할을 바꾼다.

#### 5.3.3 스터디장 위임

- **Method / URI:** `POST /api/v1/studies/{studyId}/leader-delegation`
- **인증:** 로그인 필요 + `LEADER`
- **대응 테이블:** `study_memberships`, `studies`, `study_membership_histories`
- **설명:** 현 `LEADER`를 `MANAGER`로, 대상을 `LEADER`로 한 트랜잭션에서 교체한다. 기존 경로(`/delegate-leader`)는 동사형이라 명사형으로 바꿨다.
- **요청 바디:**
    
    ```json
      { "targetMembershipId": "55" }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "스터디장이 위임되었습니다.", "data": { "leaderMembershipId": "55" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_MEMBERSHIP_NOT_FOUND`(404) / 대상이 `APPROVED` 구성원이 아님, `STUDY_ALREADY_CLOSED`(409)
- **동시성/멱등 보장:** ① 현재 스터디장 `UPDATE … SET role='MANAGER' WHERE id=:leaderMembershipId AND role='LEADER'` ② 대상 `UPDATE … SET role='LEADER' WHERE id=:target AND status='APPROVED'`. `leader_key` 유일 인덱스가 문장 단위로 검사되므로 강등 → 승격 순서로 고정한다. 대상이 이미 `MANAGER`였으면 슬롯을 교환해 `manager_count`를 유지한다. 동시 위임 두 건은 ①의 조건으로 하나만 성공한다.

#### 5.3.4 스터디 종료

- **Method / URI:** `POST /api/v1/studies/{studyId}/close`
- **인증:** 로그인 필요 + `LEADER`
- **Idempotency:** 이미 종료면 `STUDY_ALREADY_CLOSED`
- **대응 테이블:** `studies`, `assignments`, `study_memberships`, `study_member_quotas`
- **설명:** 스터디를 `CLOSED`로 전이한다(불가역). 예정·진행 중 시험이 남아 있으면 종료를 막는다. `CLOSED` 시험이 확정 전이어도 종료를 막지 않으며 채점·확정은 계속된다. 대기 신청은 `CANCELED`, 진행 중 문제집은 `CLOSED`로 전이한다. 구성원 가입은 `APPROVED`로 남겨 읽기 전용 조회를 허용하되, 구성원별 참여 상한 카운터를 1씩 되돌린다.
- **응답 (200):**
    
    ```json
      { "success": true, "message": "스터디가 종료되었습니다.", "data": { "studyId": "12" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_ALREADY_CLOSED`(409)
    - `STUDY_ACTIVE_EXAM_EXISTS`(409) / `SCHEDULED`·`IN_PROGRESS` 시험 존재 (예정 시험 취소 안내)
    - `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **동시성/멱등 보장:** `UPDATE studies SET status='CLOSED' WHERE id=? AND status <> 'CLOSED'`가 먼저 실행되고, 이후 가입 확보 문장은 `status` 조건으로 모두 실패한다.
- **외부 의존:**
    - `GET /internal/v1/exams/pending?studyId=` (contest) — 예정·진행 중 시험 수. 실패 시 fail-closed

### 5.4 외부 API — 문제집·과제

#### 5.4.1 문제집 생성

- **Method / URI:** `POST /api/v1/studies/{studyId}/assignments`
- **인증:** 로그인 필요 + `LEADER`·`MANAGER`
- **대응 테이블:** `assignments`, `assignment_problems`
- **설명:** 문제집을 생성한다. 각 문제의 현재 공개 회차를 `assignment_problems.assigned_revision_id`에 기록한다(표시용, 인정은 `problemId` 기준). 태그 단위 구성을 권장하며 `tagId`로 대표 태그를 지정한다(B-13). `startsAt` 도달 시 스케줄러가 `SCHEDULED → OPEN`으로 전이한다.
- **요청 바디:**
    
    ```json
      { "title": "10월 1주차 해시", "goal": "해시 집중 풀이", "tagId": "3", "problemIds": ["101", "102", "103"], "isPreSolvedAllowed": true, "startsAt": "2026-10-06T00:00:00+09:00", "deadlineAt": "2026-10-12T23:59:59+09:00" }
    ```
    
    - `problemIds` 1~20개, 중복 불가. `startsAt` 필수, `deadlineAt` 선택(`startsAt`보다 뒤, 지정 시 기본 시작 + 7일). 과거 `startsAt`이면 다음 스케줄러 회차에 바로 `OPEN`
- **응답 (201):**
    
    ```json
      { "success": true, "message": "문제집이 생성되었습니다.", "data": { "assignmentId": "30", "status": "SCHEDULED" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `COMMON_INVALID_REQUEST`(400), `STUDY_ALREADY_CLOSED`(409), `STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE`(409), `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:**
    - `GET /internal/v1/problems/summaries?ids=&purpose=ASSIGNMENT` (problem) — 공개 여부·현재 회차·표시 정보. 실패 시 fail-closed

#### 5.4.2 문제집 목록

- **Method / URI:** `GET /api/v1/studies/{studyId}/assignments`
- **인증:** 로그인 필요 + `APPROVED` 구성원
- **대응 테이블:** `assignments`, `assignment_member_statuses`
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | `SCHEDULED` / `OPEN` / `CLOSED` |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `startsAt`, `deadlineAt` (기본 `startsAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { "assignmentId": "30", "title": "10월 1주차 해시", "tagId": "3", "status": "OPEN", "activeProblemCount": 3, "startsAt": "2026-10-06T00:00:00+09:00", "deadlineAt": "2026-10-12T23:59:59+09:00", "myStatus": { "status": "IN_PROGRESS", "completedCount": 1, "onTimeCount": 1 } } ],
          "page": 0, "size": 20, "totalElements": 5, "totalPages": 1, "hasNext": false
        }
      }
    ```
    
    - `myStatus`: 요청자가 대상이 아니면 `null`
- **에러:**
    - `AUTH_ACCESS_DENIED`(403)

#### 5.4.3 문제집 상세·진행률

- **Method / URI:** `GET /api/v1/studies/{studyId}/assignments/{assignmentId}`
- **인증:** 로그인 필요 + `APPROVED` 구성원
- **대응 테이블:** `assignments`, `assignment_problems`, `assignment_member_statuses`, `assignment_completions`, `member_replicas`
- **설명:** 문제별 배정 스냅샷과 현재 `APPROVED` 구성원의 진행률을 반환한다. `EXCLUDED` 문제는 목록에 표시하되 진행률 분모에서 뺀다.
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "assignmentId": "30", "title": "10월 1주차 해시", "goal": "해시 집중 풀이", "tagId": "3", "status": "OPEN", "isPreSolvedAllowed": true,
          "startsAt": "2026-10-06T00:00:00+09:00", "deadlineAt": "2026-10-12T23:59:59+09:00",
          "problems": [ { "problemId": "101", "assignedRevisionId": "201", "revisionNumber": 3, "title": "최단 경로", "difficulty": 3, "tags": ["그래프"], "status": "ACTIVE", "myCompletionType": "ON_TIME" } ],
          "memberProgress": [ { "memberId": "7", "nickname": "user7", "status": "IN_PROGRESS", "completedCount": 2, "onTimeCount": 2, "activeProblemCount": 3 } ],
          "studyStats": { "avgCompletionRate": 0.67, "avgOnTimeRate": 0.50 }
        }
      }
    ```
    
    - 진행률 = 인정 문제 수 / `ACTIVE` 문제 수, 기한 내 완료율 = (`PRE_SOLVED` + `ON_TIME`) / `ACTIVE` 문제 수
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_ASSIGNMENT_NOT_FOUND`(404)

#### 5.4.4 후속 문제집 후보

- **Method / URI:** `GET /api/v1/studies/{studyId}/assignments/{assignmentId}/follow-up-candidates`
- **인증:** 로그인 필요 + `LEADER`·`MANAGER`
- **대응 테이블:** `assignment_problems`, `assignment_completions`, `assignment_member_statuses`
- **설명:** `CLOSED` 문제집에서 미해결 구성원이 있는 `ACTIVE` 문제를 미해결 인원 내림차순으로 반환한다. 후속 문제집은 이 결과로 문제집 생성(5.4.1)을 호출해 만든다.
- **응답 (200):**
    
    ```json
      { "success": true, "message": null, "data": { "candidates": [ { "problemId": "102", "title": "…", "unsolvedMemberCount": 3 } ] } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_ASSIGNMENT_NOT_FOUND`(404)

### 5.5 외부 API — 커뮤니티

#### 5.5.1 게시글 목록

- **Method / URI:** `GET /api/v1/studies/{studyId}/posts`
- **인증:** 로그인 필요 + `APPROVED` 구성원
- **대응 테이블:** `posts`, `member_replicas`
- **설명:** 고정 게시글을 먼저, 이후 최신순으로 반환한다. `HIDDEN`·`DELETED`는 제외한다.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `isNotice` | boolean | N | — | 공지만 보기 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `createdAt` (기본 고정 우선 → `createdAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { "postId": "88", "title": "이번 주 풀이 공유", "authorId": "7", "authorNickname": "user7", "isPinned": false, "isNotice": false, "commentCount": 2, "createdAt": "2026-09-28T10:00:00+09:00" } ],
          "page": 0, "size": 20, "totalElements": 12, "totalPages": 1, "hasNext": false
        }
      }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403)

#### 5.5.2 게시글 작성

- **Method / URI:** `POST /api/v1/studies/{studyId}/posts`
- **인증:** 로그인 필요 (`SUSPENDED` 차단) + `APPROVED` 구성원
- **대응 테이블:** `posts`, `studies`
- **설명:** 게시글을 작성하고 `studies.last_activity_at`을 갱신한다. 공지(`isNotice`)는 `LEADER`·`MANAGER`만 지정할 수 있다.
- **요청 바디:**
    
    ```json
      { "title": "이번 주 풀이 공유", "content": "BFS로 풀었습니다...", "isNotice": false }
    ```
    
    - `title` 1~200자, `content` 1자 이상
- **응답 (201):**
    
    ```json
      { "success": true, "message": "게시글이 작성되었습니다.", "data": { "postId": "88" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `COMMON_INVALID_REQUEST`(400), `STUDY_ALREADY_CLOSED`(409), `MEMBER_SUSPENDED`(403)

#### 5.5.3 게시글 상세

- **Method / URI:** `GET /api/v1/studies/{studyId}/posts/{postId}`
- **인증:** 로그인 필요 + `APPROVED` 구성원
- **대응 테이블:** `posts`, `comments`, `member_replicas`
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "postId": "88", "title": "이번 주 풀이 공유", "content": "BFS로 풀었습니다...", "author": { "memberId": "7", "nickname": "user7" }, "isPinned": false, "isNotice": false,
          "comments": [ { "commentId": "301", "content": "좋네요", "author": { "memberId": "8", "nickname": "lee" }, "createdAt": "2026-09-28T11:00:00+09:00" } ],
          "createdAt": "2026-09-28T10:00:00+09:00", "updatedAt": "2026-09-28T10:00:00+09:00"
        }
      }
    ```
    
    - 탈퇴 회원은 `nickname: "탈퇴한 사용자"`
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_POST_NOT_FOUND`(404)

#### 5.5.4 게시글 수정·삭제

- **Method / URI:** `PATCH /api/v1/studies/{studyId}/posts/{postId}`, `DELETE /api/v1/studies/{studyId}/posts/{postId}`
- **인증:** 로그인 필요 + 작성자 본인
- **대응 테이블:** `posts`
- **요청 바디 (PATCH):**
    
    ```json
      { "title": "수정한 제목", "content": "수정한 본문" }
    ```
    
- **응답:** PATCH `200` `{ "postId": "88" }` / DELETE `204` (`status = DELETED`)
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_POST_NOT_FOUND`(404), `STUDY_ALREADY_CLOSED`(409)

#### 5.5.5 게시글 고정·공지·숨김 (운영진)

- **Method / URI:** `PATCH /api/v1/studies/{studyId}/posts/{postId}/moderation`
- **인증:** 로그인 필요 + `LEADER`·`MANAGER`
- **대응 테이블:** `posts`
- **설명:** 공지·상단 고정 여부와 부적절한 글의 숨김을 변경한다. 기존 경로(`/pin`)를 숨김까지 포함하도록 넓혔다.
- **요청 바디:**
    
    ```json
      { "isPinned": true, "isNotice": true, "isHidden": false }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "게시글 설정이 변경되었습니다.", "data": { "postId": "88", "isPinned": true, "isNotice": true, "status": "VISIBLE" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_POST_NOT_FOUND`(404)

#### 5.5.6 댓글 작성·수정·삭제

- **Method / URI:** `POST /api/v1/studies/{studyId}/posts/{postId}/comments`, `PATCH …/comments/{commentId}`, `DELETE …/comments/{commentId}`
- **인증:** 로그인 필요 (`SUSPENDED` 차단) + `APPROVED` 구성원 (수정·삭제는 작성자, 숨김은 운영진이 게시글 고정·공지·숨김 5.5.5와 같은 방식)
- **대응 테이블:** `comments`
- **요청 바디 (POST·PATCH):**
    
    ```json
      { "content": "좋네요" }
    ```
    
- **응답:** POST `201` `{ "commentId": "301" }` / PATCH `200` / DELETE `204`
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_POST_NOT_FOUND`(404), `STUDY_COMMENT_NOT_FOUND`(404)

### 5.6 관리자 API

#### 5.6.1 스터디 강제 종료

- **Method / URI:** `POST /api/v1/admin/studies/{studyId}/close`
- **인증:** `ADMIN`
- **Idempotency:** 이미 종료면 `STUDY_ALREADY_CLOSED`
- **대응 테이블:** `studies`, `assignments`, `study_memberships`, `study_member_quotas`, `audit_logs`
- **설명:** 스터디 종료(5.3.4)와 같은 종료 처리를 하고, 같은 트랜잭션에서 감사 로그에 작업자·사유·변경 전후 상태를 기록한다. 예정·진행 중 시험 조건도 같다.
- **요청 바디:**
    
    ```json
      { "reason": "운영 정책 위반" }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "스터디가 강제 종료되었습니다.", "data": { "studyId": "12" } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `STUDY_NOT_FOUND`(404), `STUDY_ALREADY_CLOSED`(409), `STUDY_ACTIVE_EXAM_EXISTS`(409)

#### 5.6.2 회원 AC 복제본 재동기화 (P2)

- **Method / URI:** `POST /api/v1/admin/studies/replicas/member-ac/resync`
- **인증:** `ADMIN`
- **Idempotency:** 같은 회원에 진행 중 재동기화가 있으면 기존 작업 결과 반환
- **대응 테이블:** `member_ac_replicas`, `assignment_completions`, `assignment_member_statuses`, `audit_logs`
- **설명:** 이벤트 유실·DLQ 이후 특정 회원의 AC 복제본을 judge 내부 API로 다시 받아 재구성하고, 진행 중 문제집 완료 기록을 재계산한다.
- **요청 바디:**
    
    ```json
      { "memberId": "7", "reason": "DLQ 재처리 후 복구" }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "재동기화가 완료되었습니다.", "data": { "memberId": "7", "replicaCount": 58, "recalculatedAssignmentCount": 2 } }
    ```
    
- **에러:**
    - `AUTH_ACCESS_DENIED`(403), `COMMON_DEPENDENCY_UNAVAILABLE`(503)
- **외부 의존:**
    - `GET /internal/v1/submissions/accepted?context=PRACTICE&userId=` (judge) — 실패 시 재동기화 중단, 운영 알림

### 5.7 내부 API

#### 5.7.1 스터디 구성원 목록 조회 (내부)

- **Method / URI:** `GET /internal/v1/studies/{studyId}/members`
- **인증:** 서비스 계정 (contest)
- **대응 테이블:** `studies`, `study_memberships`
- **설명:** contest가 시험 생성·수정 권한(`LEADER`·`MANAGER`, 스터디 `CLOSED` 여부)을 확인할 때 호출한다. 참가 자격은 `StudyMemberJoined`·`StudyMemberLeft` 복제본으로 판단한다.
- **응답 (200):**
    
    ```json
      { "studyId": "12", "studyStatus": "ACTIVE", "members": [ { "memberId": "1", "membershipId": "40", "role": "LEADER" }, { "memberId": "7", "membershipId": "55", "role": "MEMBER" } ] }
    ```
    
- **실패 시 처리:** contest는 fail-closed (생성 거절)

#### 5.7.2 회원의 스터디 가입 조회 (내부)

- **Method / URI:** `GET /internal/v1/studies/memberships`
- **인증:** 서비스 계정 (member)
- **대응 테이블:** `study_memberships`, `studies`
- **설명:** 회원 탈퇴 차단(스터디장 여부) 판단용.
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `memberId` | string | Y | — | 회원 ID |
    | `role` | string | N | — | `LEADER` 등 |
    | `activeOnly` | boolean | N | true | `CLOSED`가 아닌 스터디의 `APPROVED` 가입만 |
- **응답 (200):**
    
    ```json
      { "memberId": "7", "items": [ { "studyId": "12", "membershipId": "40", "role": "LEADER", "studyStatus": "ACTIVE" } ] }
    ```
    

---

## 6. AI 기능 (ai)

> 작성: 담당 D · 서비스: `study-service` 내부 AI 모듈 (`ai-service` 분리 후보)
> 

### 6.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/hint-requests/**` (Gateway 라우팅이 `problems/**`와 겹치지 않도록 별도 리소스) |
| 처리 방식 | 비동기 — `202 Accepted` → LLM 생성 → `HintReady` 이벤트 → SSE 알림 |
| 원천 데이터 | 요청·이력·일일 사용량·차단 복제본 = MySQL `study` 스키마 |
| 캐시 | Redis `hint:cache:{problemRevisionId}:{level}` (TTL 30일, 회차가 키에 있어 새 회차 공개 시 자연 무효화) |
| 외부 의존 | `problem-service` 힌트 문맥 내부 API(힌트 문맥 조회2.6.6), LLM API (Spring AI) |
| 에러 코드 | 기존 `STUDY_HINT_*`는 도메인 접두어 규칙에 맞춰 `HINT_*`로 바꿨다 |
| 운영 조건 | 가드레일과 함께만 운영 활성화, 기능 토글로 즉시 비활성화 가능 |

### 6.1 외부 API — 단계별 힌트

#### 6.1.1 힌트 요청

- **Method / URI:** `POST /api/v1/hint-requests`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **Idempotency:** 같은 `(problemId, level)`에 `REQUESTED`·`GENERATING` 요청이 있으면 그 요청을 반환하고 횟수를 차감하지 않는다. 같은 단계의 `READY`·`FALLBACK`이 이미 있으면 그 결과를 반환하고 차감하지 않는다.
- **Rate Limit:** 1인 하루 20회 (`hint_daily_usages`, 캐시 적중 포함)
- **대응 테이블:** `hint_requests`, `hint_daily_usages`, `hint_block_replicas`, `outbox_events`
- **설명:** 힌트를 비동기로 요청한다. 검증 순서 = ① 문제 문맥 조회(공개 문제·대회 전용 여부, 현재 공개 회차) ② 시험·대회·진단 차단 ③ 단계 순서 ④ 기존 요청 확인 ⑤ 일일 횟수 확보. 캐시 적중이면 즉시 `READY`로 응답한다.
- **요청 바디:**
    
    ```json
      { "problemId": "101", "level": 1 }
    ```
    
    - `level`: `1`(접근 방향) / `2`(핵심 아이디어) / `3`(풀이 절차)
- **응답 (202):**
    
    ```json
      { "success": true, "message": "힌트 생성을 요청했습니다.", "data": { "hintRequestId": "300", "status": "REQUESTED", "content": null, "todayUsedCount": 4, "dailyLimit": 20 } }
    ```
    
    - 캐시 적중·기존 결과 반환: `status: "READY"`(또는 `FALLBACK`), `content` 포함
- **에러:**
    - `HINT_PROBLEM_NOT_AVAILABLE`(404), `HINT_BLOCKED_DURING_EXAM`(403), `HINT_BLOCKED_DURING_DIAGNOSIS`(403), `HINT_LEVEL_SKIPPED`(409), `HINT_DAILY_LIMIT_EXCEEDED`(429), `COMMON_DEPENDENCY_UNAVAILABLE`(503), `MEMBER_SUSPENDED`(403)
- **동시성/멱등 보장:**
    - 중복 요청: 생성 컬럼 `active_level = IF(status IN ('REQUESTED','GENERATING'), level, NULL)` + `uk_hint_requests_member_problem_active_level`. 위반 시 기존 요청을 조회해 반환
    - 일일 횟수: `UPDATE hint_daily_usages SET used_count = used_count + 1 WHERE member_id = ? AND usage_date = CURRENT_DATE AND used_count < 20` (행이 없으면 먼저 `INSERT … ON DUPLICATE KEY UPDATE`). 요청 INSERT와 같은 트랜잭션
    - 차단 조회 실패·문맥 조회 실패는 거절(fail-closed)
- **발행:**
    - `HintReady` — `READY`·`FALLBACK`·전달 직전 `BLOCKED` 종결 시
- **외부 의존:**
    - `GET /internal/v1/problems/{problemId}/hint-context` (problem) — 실패 시 fail-closed
- **프론트 계약:**
    - `202` 후 SSE(`/api/v1/notifications/subscribe`)의 `HINT_READY` 알림을 기다린다. 수신 후 힌트 요청 단건(6.1.3)으로 내용을 조회한다. 폴링하지 않는다.
    - `HintReady`의 `result`가 `BLOCKED`이면 "시험 진행 중이라 힌트를 제공할 수 없음"을 표시한다.

#### 6.1.2 문제별 힌트 이력 조회

- **Method / URI:** `GET /api/v1/hint-requests`
- **인증:** 로그인 필요 (본인)
- **대응 테이블:** `hint_requests`, `hint_daily_usages`
- **설명:** 특정 문제에 대한 본인 힌트 이력(단계 오름차순)과 오늘 사용량을 반환한다. 페이지네이션 없음(단계 3 × 회차 수).
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `problemId` | string | Y | — | 문제 ID |
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "problemId": "101",
          "hints": [ { "hintRequestId": "300", "level": 1, "status": "READY", "isFallback": false, "content": "이 문제는 그래프 탐색 유형입니다…", "requestedAt": "2026-09-28T10:00:00+09:00", "completedAt": "2026-09-28T10:00:05+09:00" } ],
          "nextAvailableLevel": 2,
          "todayUsedCount": 3,
          "dailyLimit": 20
        }
      }
    ```
    

#### 6.1.3 힌트 요청 단건

- **Method / URI:** `GET /api/v1/hint-requests/{hintRequestId}`
- **인증:** 로그인 필요 (본인)
- **대응 테이블:** `hint_requests`
- **설명:** SSE `HINT_READY` 수신 후 힌트 내용을 가져온다. `GENERATING`이면 아직 처리 중이다.
- **응답 (200):**
    
    ```json
      { "success": true, "message": null, "data": { "hintRequestId": "300", "problemId": "101", "level": 1, "status": "READY", "isFallback": false, "blockedReason": null, "content": "이 문제는 그래프 탐색 유형입니다…", "completedAt": "2026-09-28T10:00:05+09:00" } }
    ```
    
    - `status`: `REQUESTED` / `GENERATING` / `READY` / `FALLBACK` / `BLOCKED`. `BLOCKED`이면 `content`는 `null`, `blockedReason`은 `EXAM` / `DIAGNOSIS` / `DAILY_LIMIT`
- **에러:**
    - `HINT_REQUEST_NOT_FOUND`(404)

---

## 7. 알림 (notification)

> 작성: 담당 A · 서비스: `notification-service`
> 

### 7.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/notifications/**` |
| 소유 테이블 | 스키마 `notification` (`notifications`, `notification_preferences`) |
| 생성 책임 | 도메인은 이벤트만 발행하고 notification이 수신자·문구·이동 경로를 정한다 |
| 중복 방지 | `(source_event_id, member_id, type)` 유일 제약 |
| 채점 알림 범위 | `PRACTICE`만 건별 알림. `EXAM`·`CONTEST`는 결과 확정(`FINALIZED`) 시에만. 채점 실패·재처리 예정은 문맥 무관 |
| 보관 | 90일 |

#### 알림 유형 (`type`)

| 유형 | 원천 이벤트 | 수신 설정 |
| --- | --- | --- |
| `SUBMISSION_JUDGED` | `SubmissionJudged` | 끌 수 없음 |
| `SUBMISSION_FAILED` | `SubmissionFailed` | 끌 수 없음 |
| `EXAM_STARTING_SOON` / `EXAM_FINALIZED` / `EXAM_UPDATED` / `EXAM_CANCELED` | 시험 이벤트·시작 임박 스케줄러 | `isExamContestEnabled` |
| `CONTEST_STARTED` / `CONTEST_FINALIZED` / `CONTEST_CANCELED` | 대회 이벤트 | `isExamContestEnabled` |
| `STUDY_APPLICATION_DECIDED` | `StudyApplicationDecided` | `isStudyApplicationEnabled` |
| `ASSIGNMENT_PUBLISHED` / `ASSIGNMENT_DEADLINE_APPROACHING` / `ASSIGNMENT_INCOMPLETE_REPEATED` | 문제집 이벤트 | `isAssignmentEnabled` |
| `LEVEL_CHANGED` | `TagLevelChanged` (진단 결과·승급·마스터·재채점 하향·쿨타임 해제) | `isLevelChangedEnabled` |
| `HINT_READY` | `HintReady` | `isHintReadyEnabled` |
| `GITHUB_SYNC_FAILED` | `GitHubSyncFailed` | `isGithubSyncEnabled` |

### 7.1 외부 API

#### 7.1.1 실시간 알림 스트림 (SSE)

- **Method / URI:** `GET /api/v1/notifications/subscribe`
- **인증:** 로그인 필요 (Gateway가 `X-User-Id` 주입)
- **대응 테이블:** `notifications`
- **설명:** SSE로 알림을 전달한다(`text/event-stream`). 하트비트 30초, 서버가 60초마다 연결을 정상 종료하며 클라이언트가 재연결한다. 재연결 시 `Last-Event-ID` 이후 최근 24시간 미읽음을 재전송한다. 기존 초안의 하트비트 45초는 정책(30초)에 맞췄다.
- **요청 헤더:**
    
    
    | 헤더 | 필수 | 예시 값 | 설명 |
    | --- | --- | --- | --- |
    | `Last-Event-ID` | N | `1024` | 마지막으로 받은 알림 ID |
- **응답 (200, SSE):**
    
    ```
    event: notification
    id: 1024
    data: {"notificationId":"1024","type":"SUBMISSION_JUDGED","title":"채점 완료","content":"1001번 문제 채점이 완료되었습니다. (결과: AC)","linkUrl":"/submissions/91023","createdAt":"2026-10-10T21:03:12.123456+09:00"}
    
    event: heartbeat
    data: {}
    ```
    

#### 7.1.2 알림 목록 조회

- **Method / URI:** `GET /api/v1/notifications`
- **인증:** 로그인 필요
- **대응 테이블:** `notifications`
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `unreadOnly` | boolean | N | false | 미읽음만 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `createdAt` (기본 `createdAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "unreadCount": 1,
          "content": [ { "notificationId": "1024", "type": "SUBMISSION_JUDGED", "title": "채점 완료", "content": "1001번 문제 채점이 완료되었습니다. (결과: AC)", "linkUrl": "/submissions/91023", "isRead": false, "createdAt": "2026-10-10T21:03:12.123456+09:00" } ],
          "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
        }
      }
    ```
    

#### 7.1.3 미읽음 개수 조회

- **Method / URI:** `GET /api/v1/notifications/unread-count`
- **인증:** 로그인 필요
- **대응 테이블:** `notifications`
- **응답 (200):**
    
    ```json
      { "success": true, "message": null, "data": { "unreadCount": 3 } }
    ```
    

#### 7.1.4 알림 단건 읽음 처리

- **Method / URI:** `PATCH /api/v1/notifications/{notificationId}/read`
- **인증:** 로그인 필요 (본인)
- **Idempotency:** 이미 읽은 알림은 변경 없이 200
- **대응 테이블:** `notifications`
- **응답 (200):**
    
    ```json
      { "success": true, "message": "알림이 읽음 처리되었습니다.", "data": { "unreadCount": 0 } }
    ```
    
- **에러:**
    - `NOTIFICATION_NOT_FOUND`(404)

#### 7.1.5 알림 전체 읽음 처리

- **Method / URI:** `PATCH /api/v1/notifications/read-all`
- **인증:** 로그인 필요
- **대응 테이블:** `notifications`
- **응답 (200):**
    
    ```json
      { "success": true, "message": "모든 알림을 읽음 처리했습니다.", "data": { "updatedCount": 5, "unreadCount": 0 } }
    ```
    

#### 7.1.6 수신 설정 조회·변경

- **Method / URI:** `GET /api/v1/notifications/preferences`, `PATCH /api/v1/notifications/preferences`
- **인증:** 로그인 필요
- **대응 테이블:** `notification_preferences`
- **설명:** 종류별 수신 여부. 행이 없으면 모두 `true`로 응답하고 최초 변경 시 생성한다. 채점 결과는 끌 수 없어 항목이 없다.
- **요청 바디 (PATCH):**
    
    ```json
      { "isAssignmentEnabled": false }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": null, "data": { "isExamContestEnabled": true, "isStudyApplicationEnabled": true, "isAssignmentEnabled": false, "isLevelChangedEnabled": true, "isHintReadyEnabled": true, "isGithubSyncEnabled": true } }
    ```
    

---

## 8. GitHub 연동 (integration)

> 작성: 담당 A · 서비스: `integration-service`
> 

### 8.0 개요

| 항목 | 값 |
| --- | --- |
| 외부 경로 | `/api/v1/integrations/github/**` |
| 소유 테이블 | 스키마 `integration` (`github_connections`, `github_sync_jobs`) |
| 인가 | 로그인과 분리. GitHub App 설치로 선택한 저장소 1개에만 쓰기 권한 (1주차 결정 대상) |
| 동기화 대상 | `PRACTICE` AC만. 시험·대회·진단 제출 제외 |
| 재시도 | 1분 → 5분 → 30분, 최대 5회. Rate Limit은 초기화 시각 이후 |
| 저장 구조 | `{난이도}/{문제번호}-{문제명}/{언어별 파일}` + `../../README.md` |

### 8.1 외부 API

#### 8.1.1 GitHub 연동 상태 조회

- **Method / URI:** `GET /api/v1/integrations/github`
- **인증:** 로그인 필요
- **대응 테이블:** `github_connections`
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": { "status": "CONNECTED", "githubLogin": "kim-dev", "repositoryOwner": "kim-dev", "repositoryName": "algorithm-solutions", "isAutoCommitEnabled": true, "connectedAt": "2026-10-01T14:20:00.000000+09:00" }
      }
    ```
    
    - 연동이 없으면 `data: { "status": "NOT_CONNECTED" }`. 설치 ID·토큰은 응답에 포함하지 않는다.

#### 8.1.2 GitHub App 설치 주소 조회

- **Method / URI:** `GET /api/v1/integrations/github/install-url`
- **인증:** 로그인 필요
- **대응 테이블:** 없음 (`state`는 Redis, TTL 10분)
- **설명:** 저장소 1개 선택을 안내하는 GitHub App 설치 주소와 `state`를 반환한다.
- **응답 (200):**
    
    ```json
      { "success": true, "message": null, "data": { "installUrl": "https://github.com/apps/resolve-sync/installations/new?state=…" } }
    ```
    

#### 8.1.3 GitHub App 설치 완료

- **Method / URI:** `POST /api/v1/integrations/github/install`
- **인증:** 로그인 필요 (`SUSPENDED` 차단)
- **대응 테이블:** `github_connections`
- **설명:** 설치 후 돌려받은 `installationId`를 검증해 회원에 연결하고 대상 저장소 정보를 저장한다. 선택 저장소가 1개가 아니면 거절한다.
- **요청 바디:**
    
    ```json
      { "installationId": "58291032", "state": "…" }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "GitHub 저장소 연동이 완료되었습니다.", "data": { "status": "CONNECTED", "repositoryName": "algorithm-solutions", "isAutoCommitEnabled": true } }
    ```
    
- **에러:**
    - `GITHUB_INSTALLATION_INVALID`(400), `GITHUB_REPOSITORY_INVALID`(409), `GITHUB_ALREADY_CONNECTED`(409)
- **동시성/멱등 보장:** `uk_github_connections_member_id`, `uk_github_connections_installation_id`. `DISCONNECTED` 행이 있으면 같은 행을 `CONNECTED`로 재연결한다.

#### 8.1.4 연동 설정 변경

- **Method / URI:** `PATCH /api/v1/integrations/github`
- **인증:** 로그인 필요
- **대응 테이블:** `github_connections`
- **요청 바디:**
    
    ```json
      { "isAutoCommitEnabled": false }
    ```
    
- **응답 (200):**
    
    ```json
      { "success": true, "message": "연동 설정이 변경되었습니다.", "data": { "isAutoCommitEnabled": false } }
    ```
    
- **에러:**
    - `GITHUB_NOT_CONNECTED`(404)

#### 8.1.5 GitHub 연동 해제

- **Method / URI:** `DELETE /api/v1/integrations/github`
- **인증:** 로그인 필요
- **대응 테이블:** `github_connections`, `github_sync_jobs`
- **설명:** `DISCONNECTED`로 전이하고 토큰·설치 정보를 파기한다. 대기 중 Sync Job은 `CANCELED`로 전이한다. 이미 커밋된 파일은 삭제하지 않는다.
- **응답 (204)**
- **에러:**
    - `GITHUB_NOT_CONNECTED`(404)

#### 8.1.6 동기화 작업 목록

- **Method / URI:** `GET /api/v1/integrations/github/sync-jobs`
- **인증:** 로그인 필요
- **대응 테이블:** `github_sync_jobs`
- **요청 파라미터:**
    
    
    | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
    | --- | --- | --- | --- | --- |
    | `status` | string | N | — | Sync Job 상태 필터 |
    | `page` | int | N | 0 |  |
    | `size` | int | N | 20 |  |
    
    허용 정렬 필드: `createdAt` (기본 `createdAt,desc`)
    
- **응답 (200):**
    
    ```json
      {
        "success": true,
        "message": null,
        "data": {
          "content": [ { "syncJobId": "77", "submissionId": "91023", "problemId": "301", "status": "FAILED", "retryCount": 5, "commitSha": null, "errorMessage": "Rate limit exceeded", "createdAt": "…", "updatedAt": "…" } ],
          "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
        }
      }
    ```
    

#### 8.1.7 수동 동기화 요청

- **Method / URI:** `POST /api/v1/integrations/github/sync-jobs`
- **인증:** 로그인 필요
- **Idempotency:** 같은 제출의 작업이 대기·실행 중이면 그 작업을 반환
- **대응 테이블:** `github_sync_jobs`
- **설명:** 누락됐거나 연동 전에 맞힌 제출을 다시 커밋하도록 요청한다. `FAILED` 작업은 재시도 횟수를 초기화해 `PENDING`으로 되돌린다. 기존 경로(`/sync/{submissionId}`)의 오타·동사형을 리소스형으로 바꿨다.
- **요청 바디:**
    
    ```json
      { "submissionId": "91023" }
    ```
    
- **응답 (202):**
    
    ```json
      { "success": true, "message": "GitHub 커밋 작업을 등록했습니다.", "data": { "syncJobId": "77", "submissionId": "91023", "status": "PENDING" } }
    ```
    
- **에러:**
    - `GITHUB_NOT_CONNECTED`(404), `GITHUB_SYNC_NOT_ALLOWED`(409)
- **외부 의존:**
    - `GET /internal/v1/submissions/{submissionId}/accepted/code?memberId=` (judge) — 실행 시점 코드 조회
- **프론트 계약:**
    - 최종 실패만 SSE `GITHUB_SYNC_FAILED`로 알린다. 성공 여부는 동기화 작업 목록(8.1.6)으로 확인한다.

---

## 9. 관리자 API 색인

| 서비스 | 절 | API |
| --- | --- | --- |
| member | 1.6.1 ~ 1.6.3 | 회원 목록, 정지·해제, 권한 변경 |
| problem | 2.3.1 ~ 2.3.9, 2.4, 2.5.2 ~ 2.5.4 | 문제 등록·회차·공개·비공개·보관, 이미지, 태그 |
| judge | 3.2.1 ~ 3.2.5 | 채점 작업 조회, 시험 재채점, 실패 재처리, 진행 작업 복구 |
| contest | 4.6.1 ~ 4.6.7 | 대회 생성·수정·취소, `FAILED` 0점 확정, 결과 재확정 |
| study | 5.6.1 ~ 5.6.2 | 스터디 강제 종료, AC 복제본 재동기화 |

---

## 부록

### A. 서비스 간 내부 API 지도

| 제공 서비스 | 이용 서비스 | Method / URI | 용도 | 실패 시 처리 | 작성 |
| --- | --- | --- | --- | --- | --- |
| member | judge | `GET /internal/v1/members/{memberId}/tag-levels` | 레벨 접근 제한 | judge: fail-closed | E |
| member | 각 서비스 | `GET /internal/v1/members/{memberId}/status` | 정지 여부 (복제본 없을 때) | 호출 측 fail-closed | E |
| problem | judge | `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/judging/validation` | 지정 회차 검증 | 제한 재시도 후 fail-closed, 최신 회차 자동 치환 금지 | B |
| problem | judge | `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/judging/data` | 고정 회차 채점 데이터 | 제한 재시도, 작업 `RETRY_WAITING` | B |
| problem | contest, member | `GET /internal/v1/problems/{problemId}/revisions/{revisionId}/content` · `/images/{imageId}` | 고정 회차 본문·이미지 | 503 응답 | B |
| problem | study, contest | `GET /internal/v1/problems/summaries` | 문제집·시험·대회 문제 선택(회차 고정) | fail-closed (`COMMON_DEPENDENCY_UNAVAILABLE`) | B(제공)·D·C(요청) |
| problem | study | `GET /internal/v1/problems/{problemId}/hint-context` | 힌트 입력·현재 회차 | fail-closed | B(제공)·D(요청) |
| problem | member | `GET /internal/v1/problems/diagnosis-candidates` | 진단 출제 | fail-closed | B(제공)·E(요청) |
| judge | contest | `GET /internal/v1/submissions/{submissionId}/result` | 결과 대사·이벤트 유실 복구 | 확정 보류 | B |
| judge | contest, problem | `GET /internal/v1/submissions/{submissionId}/source` | 시험 결과 코드 열람·풀이 공유 스냅샷 | 거절 | B |
| judge | integration | `GET /internal/v1/submissions/{submissionId}/accepted/code` | GitHub 커밋 | Sync Job 재시도 | B |
| judge | study, member | `GET /internal/v1/submissions/accepted` | AC 복제본 재동기화 (관리자 절차 전용) | 재동기화 중단, 운영 알림 | B(제공)·D(요청) |
| contest | problem | `GET /internal/v1/exams/problem-usage` | 문제 비공개·보관 전 시험 사용 확인 | fail-closed | C |
| contest | member | `GET /internal/v1/participations/active` | 탈퇴 차단 확인 | fail-closed | C(제공)·E(요청) |
| contest | study | `GET /internal/v1/exams/pending` | 스터디 종료 전 시험 확인 (D-1) | fail-closed | C(제공)·D(요청) |
| contest | judge | `GET /internal/v1/exams/{examId}/rejudge-eligibility` | 재채점 가능 여부 | fail-closed | C(제공)·B(요청) |
| study | contest | `GET /internal/v1/studies/{studyId}/members` | 시험 생성 권한·스터디 상태 확인 | fail-closed (생성 거절) | D |
| study | member | `GET /internal/v1/studies/memberships` | 탈퇴 차단(스터디장) 확인 | fail-closed | D(제공)·E(요청) |

### B. 이벤트 목록

> 공통 헤더: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `aggregateId`, `correlationId`다
순서 값은 페이로드에 도메인 이름(`judgeAttempt`, `examRevision`, `profileVersion`, `membershipId` 등)으로 둔다. 
모든 ID는 문자열.
> 

| 이벤트명·버전 | 발행 | 구독 | 경로 | 발행 시점 | 작성 |
| --- | --- | --- | --- | --- | --- |
| `ExamSubmissionRequested` v1 | contest | judge | SQS `exam-submission-queue` | 시험 제출·자동 제출 접수 트랜잭션 | C |
| `ContestSubmissionRequested` v1 | contest | judge | SQS `exam-submission-queue` | 대회 제출 접수 트랜잭션 | C |
| `DiagnosisSubmissionRequested` v1 (확정 필요) | member | judge | SQS (경로 확정 필요) | 진단 문항 제출·자동 제출 | E |
| `SubmissionJudged` v1 | judge | notification, study, member, integration, problem | SNS 팬아웃 | `PRACTICE` 회차 정상 종결과 결과 커밋 | B |
| `ExamSubmissionJudged` v1 | judge | contest | SNS 팬아웃 | `EXAM` 회차 정상 종결 | B |
| `ContestSubmissionJudged` v1 | judge | contest | SNS 팬아웃 | `CONTEST` 회차 정상 종결 | B |
| `SubmissionFailed` v1 | judge | notification, contest (진단 채점 시 member) | SNS 팬아웃 | 실행 시도 상한 초과로 `FAILED` | B |
| `ProblemStateChanged` v1 | problem | study, contest | SNS 팬아웃 | 공개 상태·용도·회차 변경 | B |
| `ExamParticipantRegistered` v1 | contest | study | SNS 팬아웃 | 시험 참가 등록 | C |
| `ExamStarted` / `ExamClosed` / `ExamFinalized` v1 | contest | study, notification | SNS 팬아웃 | 시험 상태 전이 | C |
| `ExamUpdated` / `ExamCanceled` v1 | contest | study, notification | SNS 팬아웃 | 시작 전 수정·취소 | C |
| `ContestParticipantRegistered` v1 | contest | study | SNS 팬아웃 | 대회 참가 등록 | C |
| `ContestStarted` / `ContestEnded` / `ContestFinalized` / `ContestCanceled` v1 | contest | study, notification, problem(전용 문제 공개) | SNS 팬아웃 | 대회 상태 전이 | C |
| `StudyMemberJoined` v1 | study | notification, contest, problem | SNS 팬아웃 | 가입이 `APPROVED`가 되는 트랜잭션 | D |
| `StudyMemberLeft` v1 | study | notification, contest, problem | SNS 팬아웃 | `LEFT`·`REMOVED` 전이, 회원 탈퇴 전파 | D |
| `StudyApplicationDecided` v1 | study | notification | SNS 팬아웃 | 승인·거절·만료 | D |
| `AssignmentPublished` v1 | study | notification | SNS 팬아웃 | 문제집 `SCHEDULED → OPEN` | D |
| `AssignmentDeadlineApproaching` v1 | study | notification | SNS 팬아웃 | 마감 24시간 전 1회 | D |
| `AssignmentIncompleteRepeated` v1 (신규 제안) | study | notification | SNS 팬아웃 | 연속 2회 `INCOMPLETE` 멤버 발견 | D |
| `HintReady` v1 | study(AI) | notification, member(힌트 사용 감점, 확정 필요) | SNS 팬아웃 | 힌트 종결 | D |
| `LearningProfileUpdated` v1 | member | study | SNS 팬아웃 | 프로필 입력·변경, 태그 레벨 확정·변경 | E |
| `TagLevelChanged` v1 | member | notification | SNS 팬아웃 | 진단 결과·승급·하향·쿨타임 해제 | E |
| `MemberWithdrawn` / `MemberSuspended` v1 | member | 전 서비스 | SNS 팬아웃 | 탈퇴·정지·해제 | E |
| `GitHubSyncFailed` v1 | integration | notification | SNS 팬아웃 | Sync Job 최종 실패 | A |
- Outbox는 SNS Publish 또는 SQS SendMessage 성공 후 `PUBLISHED`로 기록한다. Consumer는 `processed_events`와 업무 변경을 같은 트랜잭션에 커밋한 뒤 SQS 메시지를 삭제한다. DB 실패 시 삭제하지 않고, 커밋 후 삭제가 실패해 재수신되면 `processed_events` 확인 후 삭제만 한다.
- SNS 구독 전달 실패 DLQ와 SQS 소비 실패 DLQ를 구분해 관측한다. 재전달 시 업무 `eventId`는 유지한다.

#### 공통 봉투 예시 — `SubmissionJudged` v1

```json
{
  "eventId": "8f2c…",
  "eventType": "SubmissionJudged",
  "schemaVersion": "v1",
  "occurredAt": "2026-10-10T21:03:12.123456+09:00",
  "aggregateId": "91023",
  "correlationId": "c-7a1…",
  "submissionId": "91023",
  "userId": "7",
  "problemId": "1001",
  "problemRevisionId": "3",
  "verdict": "AC",
  "judgeAttempt": 1,
  "submittedAt": "2026-10-10T21:02:58.000000+09:00",
  "passedCount": 20,
  "totalCount": 20,
  "maxTimeMs": 312,
  "maxMemoryKb": 20480,
  "problemDifficulty": 2,
  "problemTags": ["해시"]
}
```

- 구독 후 처리: problem(풀이 상태), study(AC 복제본·완료 재계산), member(레벨·성취도 재계산), integration(AC면 Sync Job), notification(채점 완료 알림). 모두 `judgeAttempt`가 저장값보다 클 때만 반영한다.
- `problemTags`: 이름 배열. **member의 `tag_id` 기준 집계를 위해 태그 ID 포함 여부를 확정 필요.**

#### 이벤트 상세 — `ExamSubmissionRequested` v1

| 항목 | 내용 |
| --- | --- |
| 발행 / 구독 | `contest-service` / `judge-service` (SQS `exam-submission-queue`) |
| 발행 시점 | 시험 제출·자동 제출 접수 트랜잭션에서 Outbox 기록 |
| 데이터 | `examSubmissionId`(judge 멱등키), `examId`, `participantId`, `memberId`, `problemId`, `problemRevisionId`, `language`, `sourceCode`, `receivedAt`, `submissionType`(`MANUAL` / `AUTO`) |
| 실패·중복 처리 | judge는 `processed_events`·`submissions`·`judge_jobs`를 한 트랜잭션에 저장한 뒤 메시지 삭제. `(context, source_submission_id)` 유일 제약 |
| 구독 후 처리 | `context = EXAM`, `submitted_at = receivedAt`으로 제출 생성. judge 수신 시각으로 마감을 다시 판정하지 않는다 |

```json
{
  "eventId": "...", 
  "eventType": "ExamSubmissionRequested", 
  "schemaVersion": "v1",
  "occurredAt": "2026-10-20T20:31:05.000000+09:00", 
  "aggregateId": "4001", 
  "correlationId": "...",
  "examSubmissionId": "4001", 
  "examId": "55", 
  "participantId": "411", 
  "memberId": "7",
  "problemId": "101", 
  "problemRevisionId": "201", 
  "language": "java", 
  "sourceCode": "class Solution { ... }",
  "receivedAt": "2026-10-20T20:31:05.000000+09:00", 
  "submissionType": "MANUAL"
}
```

- `ContestSubmissionRequested`는 `examSubmissionId` → `contestSubmissionId`, `examId` → `contestId`로 바꾸고 `submissionType`을 뺀 구조다.
- 큐 메시지에 코드 본문이 들어가므로 SQS 메시지 크기(256KB) 안에서 64KB 코드 상한이 유지된다.

#### 이벤트 상세 — `StudyMemberJoined` v1

| 항목 | 내용 |
| --- | --- |
| 발행 / 구독 | `study-service` / notification(가입 알림), contest(참가 자격 복제본), problem(풀이 공유 권한 복제본) |
| 발행 시점 | 가입이 `APPROVED`가 되는 트랜잭션(즉시 가입·승인) |
| 데이터 | `studyId`, `membershipId`, `memberId`, `role`, `joinedAt` |
| 실패·중복 처리 | Outbox 발행 보장. 구독 측은 `eventId`로 중복 무시 |
| 구독 후 처리 | `(studyId, memberId)` 복제본을 `membershipId` 기준 upsert. 저장된 `membershipId`보다 작은 이벤트는 무시하고, 같은 `membershipId`의 `StudyMemberLeft`가 먼저 왔으면 `Joined`를 무시한다 |

```json
{
  "eventId": "8f9d...", 
  "eventType": "StudyMemberJoined", 
  "schemaVersion": "v1",
  "occurredAt": "2026-09-28T10:00:00.000000+09:00", 
  "aggregateId": "55", 
  "correlationId": "...",
  "studyId": "12", 
  "membershipId": "55", 
  "memberId": "7", 
  "role": "MEMBER", 
  "joinedAt": "2026-09-28T10:00:00.000000+09:00"
}
```

#### 이벤트 상세 — `StudyMemberLeft` v1

| 항목 | 내용 |
| --- | --- |
| 발행 / 구독 | `study-service` / notification(강제 탈퇴 알림), contest·problem(복제본 해제) |
| 발행 시점 | `LEFT`·`REMOVED` 전이 트랜잭션 |
| 데이터 | `studyId`, `membershipId`, `memberId`, `reason`(`LEFT` / `REMOVED` / `MEMBER_WITHDRAWN`), `leftAt` |
| 구독 후 처리 | 같은 `membershipId`이면 해제, 더 작은 `membershipId`면 무시. 
시험 시작 후 탈퇴해도 이미 등록한 참가는 유지. |

```json
{
  "eventId": "...", 
  "eventType": "StudyMemberLeft", 
  "schemaVersion": "v1",
  "occurredAt": "2026-10-02T12:00:00.000000+09:00", 
  "aggregateId": "55", 
  "correlationId": "...",
  "studyId": "12", 
  "membershipId": "55", 
  "memberId": "7", 
  "reason": "REMOVED", 
  "leftAt": "2026-10-02T12:00:00.000000+09:00"
}
```

#### 이벤트 상세 — `StudyApplicationDecided` v1

| 항목 | 내용 |
| --- | --- |
| 데이터 | `studyId`, `membershipId`, `memberId`, `result`(`APPROVED` / `REJECTED` / `EXPIRED`), `decidedAt` |
| 구독 후 처리 | notification: 신청자에게 결과 알림 |

#### 이벤트 상세 — `AssignmentPublished` / `AssignmentDeadlineApproaching` / `AssignmentIncompleteRepeated` v1

| 이벤트 | 데이터 | 구독 후 처리 |
| --- | --- | --- |
| `AssignmentPublished` | `studyId`, `assignmentId`, `title`, `startsAt`, `deadlineAt`, `targetMemberIds` | 대상 구성원에게 문제집 배정 알림 |
| `AssignmentDeadlineApproaching` | `studyId`, `assignmentId`, `deadlineAt`, `incompleteMemberIds` | 미완료 구성원에게 마감 24시간 전 알림 |
| `AssignmentIncompleteRepeated` | `studyId`, `assignmentId`, `memberIds`, `recipientMemberIds`(`LEADER`·`MANAGER`) | 운영진에게 연속 미완료 멤버 알림 (자동 강퇴 없음) |

> `AssignmentIncompleteRepeated`는 정책 §7.1 "연속 2회 INCOMPLETE 멤버는 운영진에게 알림"을 위한 신규 이벤트다. 
**notification 알림 종류 목록(정책 §9.2)에 추가 필요.**
> 

#### 이벤트 상세 — `HintReady` v1

| 항목 | 내용 |
| --- | --- |
| 발행 / 구독 | `study-service`(AI 모듈) / notification (member 구독은 확정 필요) |
| 발행 시점 | 힌트 요청이 `READY`·`FALLBACK`·`BLOCKED`(전달 직전 재확인)로 종결되는 트랜잭션 |
| 데이터 | `hintRequestId`, `memberId`, `problemId`, `level`, `result` |
| 구독 후 처리 | notification: 요청자에게 SSE 알림, `result`에 따라 문구 구분. member: `READY`·`FALLBACK`이면 `hint_usage_replicas` 기록(힌트 사용 AC 1/2점) |

```json
{
  "eventId": "...", 
  "eventType": "HintReady", 
  "schemaVersion": "v1",
  "occurredAt": "2026-09-28T10:00:05.000000+09:00", 
  "aggregateId": "300", 
  "correlationId": "...",
  "hintRequestId": "300", 
  "memberId": "7", 
  "problemId": "101", 
  "level": 1, 
  "result": "READY"
}
```

- `result`: `READY` | `FALLBACK` | `BLOCKED`

| 항목 | 내용 |
| --- | --- |
| 발행 / 구독 | `member-service` / notification |
| 발행 시점 | 레벨 재계산 결과 변경, 진단 `SCORED`, 쿨타임 해제 스케줄러 |
| 데이터 | `memberId`, `tagId`, `tagName`, `fromLevel`, `toLevel`, `reasonType`(`DIAGNOSIS` / `PROMOTION` / `REJUDGE` / `COOLDOWN_RELEASED`), `diagnosisId` |
| 구독 후 처리 | `LEVEL_CHANGED` 알림 (정책 문구 예시) |

### C. 상태 전이

> 원본: 정책 및 상태 v1.0의 §10. API에서 자주 쓰는 전이만 요약한다.
> 

| 대상 | 전이 | 참고 API |
| --- | --- | --- |
| 제출 | `QUEUED → JUDGING → COMPLETED`, `JUDGING → RETRY_WAITING → QUEUED`, `→ FAILED`, `FAILED → QUEUED`(재처리), `COMPLETED → QUEUED`(재채점) | 3.1.2, 3.2.3, 3.2.4 |
| 채점 작업 | `PENDING → RUNNING → SUCCEEDED`, `RUNNING → RETRY_WAITING → PENDING`, `→ FAILED` | 3.2.1, 3.2.5 |
| 문제 공개 상태 | `PRIVATE ↔ PUBLIC`, `→ ARCHIVED` | 2.3.7 ~ 2.3.9 |
| 진단 응시 | `IN_PROGRESS → CLOSED → SCORED` | 1.5.1, 1.5.6 |
| 시험 | `SCHEDULED → IN_PROGRESS → CLOSED → FINALIZED`, `SCHEDULED → CANCELED` | 4.1.4, 4.1.5, 4.5.3 |
| 시험 참가자 | `REGISTERED → STARTED → FINISHED`, `→ ABSENT`, `→ CANCELED`(시작 전) | 4.2.1 ~ 4.2.3, 4.2.10 |
| 시험·대회 제출 | `ACCEPTED → REQUESTED → JUDGED`, `REQUESTED → FAILED` | 4.2.8, 4.4.5 |
| 대회 | `SCHEDULED → RUNNING → ENDED → FINALIZED`, `SCHEDULED → CANCELED` | 4.5.1 |
| 스터디 가입 | `PENDING → APPROVED / REJECTED / CANCELED / EXPIRED`, `APPROVED → LEFT / REMOVED` | 5.2.2, 5.2.3, 5.2.5 |
| 힌트 요청 | `REQUESTED → GENERATING → READY / FALLBACK / BLOCKED`, `REQUESTED → BLOCKED` | 6.1.1 |
| GitHub Sync Job | `PENDING → RUNNING → SUCCEEDED`, `→ RETRY_WAITING / RATE_LIMITED → PENDING`, `→ FAILED`, `→ CANCELED` | 8.1.7 |

---

end.