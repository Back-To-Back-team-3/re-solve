# Problem 내부 HTTP Stub

`internal-stub` 프로필은 API 명세서 §2.6.1~§2.6.5·§2.6.8~§2.6.10을 고정 개발 데이터로 제공한다. DB 저장, S3 업로드 수명주기, 실제 상태 변경은 수행하지 않는다.

## 실행

Problem 애플리케이션 전체를 시작하므로 저장소 루트에서 `.env` 등 로컬 환경 설정과 MySQL을 먼저 준비한다. 공통 설정의 Flyway가 시작 시 DB에 접속한다. Redis 클라이언트도 기존 로컬 설정을 사용한다. Stub 요청 처리 자체는 DB·Redis·LocalStack에 접근하지 않는다.

```sh
SPRING_PROFILES_ACTIVE=local,internal-stub ./gradlew :problem-service:bootRun
```

Stub 테스트만 실행할 때는 다음 명령을 쓴다.

```sh
./gradlew :problem-service:test --tests 'com.backtoback.problem.problem.controller.InternalProblemControllerTest'
```

공통 컨트롤러와 Stub 구현체는 `internal-stub` 프로필, `problem.internal-api.enabled=true`, `problem.internal-api.implementation=stub`, `problem.internal-stub.enabled=true`가 모두 맞을 때 등록한다. 기본·운영 프로필에는 구현 선택값이 없어 경로와 서비스 Bean이 등록되지 않는다. 활성 Stub 경로는 아래 고정 데이터로 응답하므로 실제 Problem 구현 Bean과 Stub 구현 Bean을 함께 등록하지 않는다.

실제 구현을 등록할 때는 일반 프로필에서 `problem.internal-api.enabled=true`, `problem.internal-api.implementation=real`과 실제 `InternalProblemService` Bean을 함께 제공한다. 설정 조합이 일치하지 않으면 컨트롤러와 Stub Bean 모두 등록하지 않아 누락 의존성으로 기동이 실패하지 않는다.

## 고정 입력과 응답

| 시나리오 | 입력 | 결과 |
| --- | --- | --- |
| 공개 문제 | `problemId=301`, `revisionId=1001` | GENERAL·PUBLIC, 태그 1, Java 실행 프로필, 테스트 501·502 |
| 공개 문제 요약 | `ids=301` | 문제 회차 1001, GENERAL·PUBLIC |
| 대회 전용 문제 | `problemId=601`, `revisionId=2001`, `contestId=8`, `rowVersion=7`, `actorMemberId=7` | CONTEST 요약 가용, 최초 연결 버전 8 |
| 비공개 문제 요약 | `ids=302` | `isAvailable=false`, 회차 ID와 번호가 null, 언어 목록 빈 배열 |
| 통과 회차 없는 문제 요약 | `ids=303` | `isAvailable=false`, 회차 ID와 번호가 null, 언어 목록 빈 배열 |
| 없는 문제 요약 | `ids=999` | 요청 ID와 `isAvailable=false` |
| 없는 문제·회차 | `problemId=999` 또는 알려진 문제의 다른 회차 ID | `PROBLEM_NOT_FOUND` 또는 `PROBLEM_REVISION_NOT_FOUND` (404) |
| 없는 관리자 검증 | `validationId=9999` | `PROBLEM_VALIDATION_NOT_FOUND` (404) |
| 대회 연결 버전 충돌 | 601 연결 요청의 `rowVersion=6` | `PROBLEM_VERSION_CONFLICT` (409) |
| 대회 연결 상태 충돌 | 기준 버전은 7이지만 대회·회차·관리자 입력이 고정 연결과 다름 | `PROBLEM_INVALID_STATE_TRANSITION` (409) |
| 상태 작업 | `301/problem-hide-301`, `302/problem-hide-302`, `301/problem-restore-301` | COMMITTED, PENDING, ABORTED 스냅샷 |

### 호출 예시

```sh
curl -i 'http://localhost:8080/internal/v1/problems/301/revisions/1001/judging/validation'

curl -i 'http://localhost:8080/internal/v1/problems/301/revisions/1001/content' \
  -H 'X-Access-Context: CONTEST:8'

curl -i 'http://localhost:8080/internal/v1/problems/summaries?ids=301&ids=302&ids=303&ids=999&ids=601&purpose=CONTEST'

curl -i 'http://localhost:8080/internal/v1/problems/validations/2001/data' \
  -H 'X-Correlation-Id: stub-request-1'

curl -i -X PUT 'http://localhost:8080/internal/v1/problems/601/contest' \
  -H 'Content-Type: application/json' \
  --data '{"contestId":"8","problemRevisionId":"2001","rowVersion":7,"actorMemberId":"7"}'

curl -i 'http://localhost:8080/internal/v1/problems/301/operations/problem-hide-301'
```

요약 조회의 `purpose`는 `ASSIGNMENT`, `EXAM`, `CONTEST` 중 하나이며 `ids`는 반복 파라미터로 최대 50개까지 보낸다. 본문·이미지에는 `X-Access-Context: EXAM:{id}`, `CONTEST:{id}`, `DIAGNOSIS:{id}` 중 하나가 필요하다. 입력 형식·경로·회차·대회 연결별 400·404·409 오류 코드는 API 명세서를 따른다.

## 개발 파일

- 본문 이미지 `image:701`은 `problem-service/src/main/resources/internal-stub/images/701.svg`에서 읽는다.
- 테스트 참조 `test-cases/501.json`·`502.json`은 `problem-service/src/main/resources/internal-stub/test-cases/`의 실제 JSON 파일이다. 응답 SHA-256은 파일 바이트를 기준으로 하며 개발 S3 버킷이 버전 관리되지 않아 `objectVersion`은 null이다.
- Judge가 LocalStack에서 테스트 파일을 읽도록 하려면 기존 업로드 스크립트로 파일을 올린다.

```sh
./problem-service/scripts/seed-testcase-upload.sh problem-service/src/main/resources/internal-stub/test-cases
```

## 권한 경계와 교체 지점

API 명세서 §2.6.3~§2.6.4와 서비스 간 인증 결정에 따라 Problem은 호출 서비스가 확인한 문맥의 형식과 고정 회차 연결만 처리한다. 참가·진단 열람 권한을 대신 확인하지 않으므로 해당 내부 API에 별도 403 시나리오를 추가하지 않는다. 권한 거절은 호출 서비스 경로에서 검증한다. 대회 연결도 C가 ADMIN 여부를 확인한 요청을 받으며 Stub은 Problem 계약에 적힌 버전·연결 충돌을 재현한다.

컨트롤러는 `InternalProblemService`와 명세의 요청·응답별 record DTO에 의존한다. 실제 Problem 구현은 같은 인터페이스를 구현하고 `problem.internal-api.enabled=true`, `problem.internal-api.implementation=real`을 실제 Bean과 함께 제공하면 경로·요청·응답 DTO·오류 코드 계약을 재사용할 수 있다. Stub 빈은 `internal-stub` 프로필과 Stub 활성 설정에서만 등록한다. 실제 구현과 Stub 구현을 동시에 등록하지 않는다. 파일 업로드·영속 멱등·상태 전이는 해당 기능 구현 범위에서 기존 계약을 따른다.
