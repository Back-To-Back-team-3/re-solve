# 시험·대회 내부 HTTP Stub 사용 가이드

Member·Study·Problem·Judge가 시험·대회 실제 업무 구현 없이 내부 HTTP 연동을 확인할 때 사용합니다.
제공 계약은 [API 명세서 v1.1](../dev/specifications/api-spec.md)의 §4.7입니다.
정상 응답은 §0.3의 데이터 객체를 직접 반환하고 오류는 공통 오류 형식을 사용합니다.

## 1. 실행 선택

기존 `local` 실행에 `internal-stub` 프로필을 추가합니다. 기존 MySQL·Redis·공통 JPA 감사 설정은 유지합니다.
환경 변수와 로컬 인프라는 [환경 변수 예시](../operations/env-example.md)를 따릅니다.
Stub은 DB를 조회하거나 변경하지 않지만 애플리케이션 기동에는 기존 local 인프라가 필요합니다.
환경 변수는 실행할 터미널에서 설정하며 비밀값을 명령 예시나 저장소 파일에 넣지 않습니다.

```sh
./gradlew :contest-service:bootRun --no-problems-report \
  --args='--spring.profiles.active=local,internal-stub --server.port=8083'
```

`8083`은 이 가이드에서 선택한 호출 예시 포트입니다. 다른 실행 서비스와 겹치면 실행 인자와 아래 주소를 함께 바꿉니다.

| 프로필·선택 | 내부 Controller | Stub Service |
| --- | --- | --- |
| 기본 / local 단독 / internal-stub 단독 | 미등록 | 미등록 |
| local,internal-stub + `contest.internal-http.mode=stub` | 등록 | 등록 |
| local,internal-stub + `contest.internal-http.mode=disabled` | 미등록 | 미등록 |
| `contest.internal-http.mode=real` + 실제 Service 구현 | 등록 | 미등록 |

두 개발 프로필을 함께 선택하면 `application-internal-stub.yaml`이 `stub` 값을 제공합니다.
속성값만 `stub`으로 바꾸어도 두 프로필이 없으면 등록하지 않습니다.
`real`은 실제 구현을 추가한 뒤 사용합니다. 현재 Stub을 실제 구현으로 자동 대체하지 않습니다.
운영 프로필·배포 설정은 변경하지 않습니다.

내부 호출은 서비스 직접 주소를 사용합니다. 인증 토큰·시나리오 전용 헤더는 필요하지 않습니다.
외부 접근 경계는 [서비스 간 인증 방식](../records/decisions/2026-10-14-internal-service-auth.md)을 따릅니다.

## 2. 고정 개발 데이터

아래 ID·코드·시각은 개발용 데이터이며 실제 회원·시험·문제 데이터가 아닙니다.
시나리오 기준 `serverNow`는 `2026-10-20T20:15:00+09:00`으로 고정되어 벽시계에 따라 만료되지 않습니다.
문제 `101`·회차 `1001`, 문제 `102`·회차 `1002`를 모든 문맥에 고정합니다. 시험은 각 50점입니다.

| 문맥 | ID | 상태 | 스터디 | 종료 후 일반 열람 |
| --- | --- | --- | --- | --- |
| EXAM | 51 | SCHEDULED | 12 | 해당 없음 |
| EXAM | 55 | IN_PROGRESS | 12 | 해당 없음 |
| EXAM | 56 | CLOSED | 13 | 해당 없음 |
| EXAM | 57 | FINALIZED | 13 | 해당 없음 |
| EXAM | 58 | CANCELED | 13 | 해당 없음 |
| EXAM | 59 | FINALIZED, 참가자 없음 | 13 | 해당 없음 |
| CONTEST | 71 | SCHEDULED | — | 불가 |
| CONTEST | 75 | RUNNING | — | 불가 |
| CONTEST | 76 | ENDED | — | 불가 |
| CONTEST | 77 | FINALIZED | — | 가능 |
| CONTEST | 78 | CANCELED | — | 불가 |
| CONTEST | 79 | ENDED | — | 가능 |
| CONTEST | 80 | FINALIZED, 참가자 없음 | — | 가능 |

회원 `7`은 진행 문맥의 실제 입장자이며, 종료 문맥은 FINISHED입니다.
회원 `8`은 예정·진행 문맥의 REGISTERED 참가자이고 미확정 종료 문맥은 ABSENT입니다. 확정 문맥에서는 실제 입장한 FINISHED 참가자입니다.
회원 `9`는 진행 문맥에서 개인 종료한 FINISHED 참가자이고 종료 문맥에서는 미입장 ABSENT입니다.
참가 ID는 개발 문맥 ID 뒤에 회원 ID를 붙인 문자열입니다. 예를 들어 시험 `55`·회원 `7`은 `557`입니다.

- 실제 입장·미확정 조회: 회원 `7`·`9`는 탈퇴 차단, 단순 등록자 `8`과 미등록 회원은 빈 결과입니다.
- 힌트 복구: 회원 `7`·`8`은 문맥 `51,55,71,75`, 개인 종료 회원 `9`는 `55,75`를 반환합니다.
- 문제 사용: 문제 `101`·`102`는 예정·진행 문맥만 반환합니다. `103`과 미등록 문제는 빈 결과입니다.
- 미등록 스터디·회원의 목록은 빈 배열입니다. 단건 문맥·접수·시험이 없으면 명세 오류를 반환합니다.
- 목록 기본 크기는 20, 허용 크기는 1~100입니다. 응답 `nextCursor`를 같은 대상 조회의 `after`에 그대로 전달합니다.
- 커서는 대상·조회 종류·결과 버전에 묶이며, 잘못된 커서·타 조회 커서는 400입니다. 마지막 페이지는 `nextCursor=null`, `hasMore=false`입니다.

| 제출 문맥·접수 | 회원 | 부모 | 판정 | 공유 |
| --- | --- | --- | --- | --- |
| EXAM / 5501 | 7 | 55 IN_PROGRESS | AC | 409 |
| EXAM / 5601 | 7 | 56 CLOSED | AC | 409 |
| EXAM / 5701 | 7 | 57 FINALIZED | AC | 가능 |
| EXAM / 5702 | 8 | 57 FINALIZED | WA | 409 |
| CONTEST / 7501 | 7 | 75 RUNNING | AC | 409 |
| CONTEST / 7701 | 7 | 77 FINALIZED | AC | 가능 |
| CONTEST / 7702 | 8 | 77 FINALIZED | WA | 409 |

타인 접수 공유는 403입니다. 공유 성공은 `Cache-Control: private, no-store`와 불변 Java 예제 코드를 반환합니다.
확정 결과 버전은 `1`입니다. 다른 버전은 404, 미확정 문맥은 409입니다.
시험 결과는 점수 문자열, 대회 결과는 해결 수·패널티 숫자를 반환하고 문맥에 해당하지 않는 필드는 null입니다.
문제 `102`의 미제출 결과와 회원 `9`의 미집계 결과도 포함합니다. 결과에는 코드·이메일·숨김 테스트가 없습니다.
재채점 가능 여부는 `55(IN_PROGRESS)`·`56(CLOSED)`만 true, `51`·`57`·`58`은 false입니다.

## 3. Guard 입력과 응답

고정 작업 ID는 `stub-{소문자 자원 유형}-{자원 ID}`, 기대 원천 버전은 `10`입니다.
예를 들어 문제 `103`은 `stub-problem-103`·`PROBLEM_HIDE`·`expectedSourceVersion="10"`을 사용합니다.
지원 자원은 아래 표에 한정합니다. 목록 밖 Guard 입력은 400입니다.

| 자원 | ID | prepare 결과 |
| --- | --- | --- |
| MEMBER / WITHDRAWAL | 7 | 409 WITHDRAWAL_BLOCKED_ACTIVE_EXAM |
| MEMBER / WITHDRAWAL | 8 | 200 PREPARED·PENDING·ready=true |
| MEMBER / WITHDRAWAL | 10 | 200 BLOCKED·COMMITTED·ready=false |
| MEMBER / WITHDRAWAL | 11 | 200 OPEN·ABORTED·ready=false |
| STUDY / STUDY_CLOSE | 12 | 202 PREPARED·PENDING·ready=false, canceledExamIds=["51"], blockingExamIds=["55"] |
| STUDY / STUDY_CLOSE | 13 | 200 PREPARED·PENDING·ready=true. CLOSED 시험은 차단하지 않음 |
| PROBLEM / PROBLEM_HIDE | 101,102 | 409 PROBLEM_IN_ACTIVE_EXAM |
| PROBLEM / PROBLEM_HIDE | 103 | 200 PREPARED·PENDING·ready=true, 준비 전 OPEN |
| PROBLEM / PROBLEM_HIDE | 104 | 200 PREPARED·PENDING·ready=true, 준비 전 BLOCKED |
| PROBLEM / PROBLEM_HIDE | 105 | 200 BLOCKED·COMMITTED·ready=false |
| PROBLEM / PROBLEM_HIDE | 106 | 200 OPEN·ABORTED·ready=false |
| PROBLEM / PROBLEM_HIDE | 107 | 200 BLOCKED·ABORTED·ready=false |

- 동일 고정 입력은 항상 같은 응답을 반환합니다. 고정 작업 ID로 기대 버전을 `11`로 바꾸면 422 IDEMPOTENCY_KEY_CONFLICT입니다.
- 다른 작업 ID·기대 버전 `10`은 409 CONTEST_OPERATION_BLOCKED, 다른 작업 ID·오래된 버전 `9`는 409 SOURCE_VERSION_CONFLICT입니다.
- complete는 `sourceVersion="11"`, cancel은 `"10"`과 1~500자 `reason`을 전달합니다. 작업 ID는 1~100자입니다.
- complete는 BLOCKED·COMMITTED, cancel은 준비 전 OPEN/BLOCKED·ABORTED를 반환합니다. 완료·취소는 200입니다.
- 고정 COMMITTED 작업의 cancel, 고정 ABORTED 작업의 complete는 409 OPERATION_ALREADY_COMPLETED입니다.
- 완료·취소의 다른 작업 ID는 409 OPERATION_NOT_CURRENT, 잘못된 버전은 409 SOURCE_VERSION_CONFLICT입니다.
- 스터디 `12`는 complete가 차단되지만 cancel은 가능합니다. 준비가 거절된 회원·문제의 complete도 차단됩니다.

응답은 호출마다 고정 카탈로그에서 계산합니다. complete 이후 prepare를 호출해도 원래의 PREPARED 시나리오가 다시 나옵니다.
완료·취소 후 prepare의 보존 결과는 별도 종결 시나리오 ID로 확인합니다.
스터디 준비의 취소 시험 목록은 응답 예시이며 다른 조회의 시험 상태를 변경하지 않습니다.
실제 Guard 저장·영속 멱등·분산 경합·시험 취소·Outbox·이벤트 복구는 제공하지 않습니다.

## 4. HTTP 호출 예시

```sh
base=http://localhost:8083

# §4.7.1 EXAM 문맥 검증. CONTEST는 context=CONTEST, contextId=75로 변경
curl -sS -X POST "$base/internal/v1/contest/contexts/validate" \
  -H 'Content-Type: application/json' \
  -d '{"context":"EXAM","contextId":"55","memberId":"7","problemId":"101","problemRevisionId":"1001","action":"RUN"}'

# §4.7.2 실제 입장·미확정 참가
curl -sS "$base/internal/v1/contest/members/7/active-participations"

# §4.7.3 문제 준비. 스터디 대기는 STUDY/12·stub-study-12·STUDY_CLOSE로 변경
curl -sS -i -X POST "$base/internal/v1/contest/guards/PROBLEM/103/prepare" \
  -H 'Content-Type: application/json' \
  -d '{"operationId":"stub-problem-103","operationType":"PROBLEM_HIDE","expectedSourceVersion":"10"}'

# §4.7.4 완료·취소는 각각 독립된 고정 응답 예시
curl -sS -X POST "$base/internal/v1/contest/guards/PROBLEM/103/complete" \
  -H 'Content-Type: application/json' \
  -d '{"operationId":"stub-problem-103","sourceVersion":"11","reason":"개발 연동 확인"}'
curl -sS -X POST "$base/internal/v1/contest/guards/PROBLEM/104/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"operationId":"stub-problem-104","sourceVersion":"10","reason":"개발 취소 확인"}'

# §4.7.5~§4.7.7 상태·고정 문제·힌트 복구
curl -sS "$base/internal/v1/contest/studies/13/exams?size=1"
curl -sS "$base/internal/v1/contest/problems/101/usages"
curl -sS "$base/internal/v1/contest/hint-blocks?memberId=8&size=2"

# §4.7.8~§4.7.9 공유 코드와 같은 문맥·회차·회원의 확정 결과
curl -sS -i "$base/internal/v1/contest/submissions/EXAM/5701/shareable?memberId=7"
curl -sS "$base/internal/v1/contest/results/EXAM/57?resultRevision=1&size=2"
curl -sS "$base/internal/v1/contest/results/CONTEST/77?resultRevision=1"

# §4.7.10 기존 재채점 확인 경로
curl -sS "$base/internal/v1/exams/56/rejudge-eligibility"
```

## 5. 테스트와 실제 구현 교체

- `InternalContestControllerTest`: 11경로의 요청 검증·응답 타입·필드·상태·캐시 헤더·공통 오류.
- `InternalContestStubServiceTest`: 권한·상태별 응답, 조회 간 ID 일치, Guard 중복·상충·종결 시나리오, 커서 페이지.
- `InternalContestHttpConditionTest`: 프로필·구현 선택에 따른 동시 등록·비활성화와 실제 구현 선택 시 Stub 제외.

```sh
./gradlew :contest-service:test --no-problems-report
```

전체 서비스 테스트의 기존 컨텍스트 테스트는 MySQL·Redis Testcontainers를 사용하므로 Docker가 필요합니다.
MVC slice와 고정 데이터·설정 선택 테스트는 DB에 연결하지 않습니다.

실제 구현에서는 `InternalContestService`를 구현하고 `contest.internal-http.mode=real`일 때만 등록합니다.
Controller·일반 요청/응답 DTO·기존 경로·헤더·오류 계약을 재사용합니다.
고정 카탈로그를 DB·실제 권한·서버 시간 판정으로 교체하고 Guard 트랜잭션·원천 버전·영속 멱등·복구와 이벤트 처리를 연결합니다.
재채점 조회 자체는 작업 접수·확정 방지 수단이 아니며 Judge가 제출 상태·회차·입력을 따로 검증합니다.
이 Stub 제공은 실제 시험·대회 기능의 완성을 의미하지 않습니다.
