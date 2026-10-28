# 백엔드 코드 구조 기준

- 상태: 채택
- 결정일: 2026-10-14 (백엔드 코드 구조 기준)
- 논의: [261015목](https://app.notion.com/p/261015-3faac7091a7480259b1de0de6dfde369?) — Today > 백엔드 코드 구조 기준

## 결정

팀 코드 구조 기준(패키지·계층·응답 구조)을 형식화하고, 서비스 간에 같은 클래스는 공통 모듈(`common`)로 옮긴다.

- 기준은 컨벤션·빌드 매뉴얼 6. 코드 구조에 둔다.
- 공통 모듈에는 공통 응답(`ApiResponse`·`PageResponse`·`ApiErrorResponse`), 오류 처리(`ErrorCode` 인터페이스·`CommonErrorCode`·`BusinessException`·`GlobalExceptionHandler`), `BaseTimeEntity`를 둔다.
- 에러 코드는 공통 모듈의 `ErrorCode` 인터페이스를 서비스별 enum이 구현한다. `CommonErrorCode`에는 여러 서비스가 쓰는 코드(`COMMON_*`, `IDEMPOTENCY_KEY_CONFLICT`, `AUTH_TOKEN_INVALID`, `AUTH_ACCESS_DENIED`)만 둔다.
- `api-gateway`는 WebFlux 기반이라 이 기준과 공통 모듈의 MVC 의존 부분을 적용하지 않는다.
- Outbox Relay·ShedLock 공통 구성은 기획서 2.1(담당 A `common`)을 따르며, 모듈 경계는 [서비스 공통 모듈 도입](2026-10-15-common-module.md)의 이벤트·스케줄링 공통 구조를 따른다.

## 채택 근거

- 결정 시점에 같은 공통 응답 계약(API 명세서 0.3)이 세 가지 이름으로 구현되어 있었다.

| 항목 | member | problem (`feat/35`) | contest (`feat/25`) |
| --- | --- | --- | --- |
| 공통 응답 | `global/response`, `ApiResponse.ok`, `ErrorResponse` | `global/api`, `ApiResponse.success`, `ApiErrorResponse` | `global/response` |
| 예외 | `BusinessException(ErrorCode)` | 오류별 예외 클래스 9개 | 오류별 예외 클래스 11개 |
| 엔티티 패키지 | `domain` | `entity` | `domain` |
| 엔티티 생성 | 정적 팩토리 | public 생성자 | Builder + 팩토리 |

- 오류별 예외 클래스는 에러 코드 enum과 1:1이라 같은 정보를 두 번 관리한다.
- 공통 클래스를 서비스마다 복사하면 명세 0.3이 바뀔 때 서비스 수만큼 고쳐야 한다.
- 기준이 문서에 있으면 사용하는 AI 도구와 관계없이 같은 구조로 구현한다.

## 기각한 대안

- **서비스별 자율:** 리뷰어가 서비스마다 다른 구조를 읽어야 하고, AI가 서비스마다 다른 기존 코드를 따라 해 차이가 계속 커진다.
- **오류별 예외 클래스 (problem·contest 방식):** 중복 관리 문제. 예외 타입으로 분기할 필요가 있는 곳이 없다.
- **공통 모듈 없이 member의 `global`을 복사:** 공통 응답·오류 형식 변경이 서비스 수만큼 반복된다.

## 재검토 조건

- 서비스가 공통 모듈과 다른 배포 주기를 가져야 하거나, 공통 모듈 변경이 여러 서비스의 동시 배포를 자주 강제하면 공통 모듈 범위를 다시 검토한다.
- 예외 타입별로 다른 처리(재시도·로깅 수준 등)가 필요한 흐름이 생기면 예외 클래스 정책을 다시 검토한다.
