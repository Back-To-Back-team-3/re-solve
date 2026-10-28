# 공통 모듈 적용 가이드

서비스 공통 응답·예외·사용자 식별·요청 추적·시간 관리를 위한 가이드입니다.
각 서비스는 필요한 모듈을 선택하고 설정을 등록합니다.

도입 배경과 설계 선택은 [서비스 공통 모듈 도입](../records/decisions/2026-10-15-common-module.md)을 참고합니다. 기능별 계약은 다음 문서를 따릅니다.

- [API 명세서 v1.1](../dev/specifications/api-spec.md) — §0 공통 규약
- [도메인 및 데이터베이스 v1.0](../dev/specifications/erd.md) — §5 공통 컬럼 규칙
- [스케줄러 v1.0](../dev/specifications/scheduler.md) — §0.1 공통 규칙

---

## 1. 의존성 선택

| 모듈 | 제공 기능 | 서비스가 구성할 부분 |
| --- | --- | --- |
| `common-core` | 응답·예외·Clock | 도메인 DTO·오류·입력 검증 |
| `common-web` | MVC 오류·사용자 인증 연결·요청 추적 | 웹 실행 환경·보안 체인·업무 권한 |
| `common-jpa` | 생성·수정 시각·JPA 감사 | JPA 실행 환경·DB·스캔 설정 |
| `common-event` (설계) | 이벤트 메타데이터·직렬화·발행·수신 공통 처리 | payload·업무 상태·순서 판단·트랜잭션 |
| `common-scheduling` (설계) | JDBC ShedLock·공통 이벤트 정리 구성 | DB·잠금 등록·도메인 작업 |

서비스의 `build.gradle`에서 필요한 의존성만 추가합니다.

```groovy
implementation project(':common-core')
implementation project(':common-web')
implementation project(':common-jpa')
```

- `common-web`은 `common-core`를 `api` 의존성으로 제공하므로 core 타입도 직접 사용할 수 있습니다.
- `common-jpa`는 `common-core`를 내부 구현 의존성으로 사용합니다.
  - 서비스 코드에서 core 타입을 직접 참조하면 `common-core`도 선언합니다.
- 세 모듈은 Java 라이브러리입니다.
  - 웹 서버·보안 설정·DB 연결은 서비스에서 구성합니다.

---

## 2. 모듈별 적용

의존성 추가 후 사용할 설정을 등록합니다.

- 명시적 등록: 서비스 설정 클래스의 `@Import`에 필요한 타입을 지정합니다.
- 스캔으로 등록: 사용할 공통 구성 요소를 컴포넌트 스캔 범위에 포함합니다.

아래는 세 모듈을 모두 사용하는 서비스의 등록 예시입니다. 선택하지 않은 기능의 import는 제외합니다.

```java
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.logging.HttpClientConfiguration;

/**
 * 서비스에서 사용할 공통 MVC·추적·JPA 감사 구성을 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@Import({
    GlobalExceptionHandler.class,
    CorrelationIdFilter.class,
    HttpClientConfiguration.class,
    JpaAuditingConfiguration.class
})
public class CommonConfiguration {
}
```

### 2.1 common-core — 응답·예외·시간

- **성공 응답**
  - 외부 JSON 응답은 [ApiResponse](../../common-core/src/main/java/com/backtoback/common/api/ApiResponse.java)로 감쌉니다.
  - 목록은 [PageResponse](../../common-core/src/main/java/com/backtoback/common/api/PageResponse.java)의 `from(Page<T>)`으로 변환합니다.
  - DTO의 문자열 ID 변환과 페이지 입력 제한은 서비스에서 처리합니다.
  - 내부 API 성공 응답, 204·SSE·이미지 응답은 래퍼 적용 대상에서 제외합니다.
  - 적용 기준은 API 명세서 §0.3 공통 성공 / 에러 응답을 따릅니다.
- **오류 코드·예외**
  - 서비스별 오류 enum은 [ErrorCode](../../common-core/src/main/java/com/backtoback/common/exception/ErrorCode.java)를 구현합니다.
  - `getCode()`, `getStatus()`, `getMessage()`로 오류 문자열·HTTP 상태·공개할 안내를 제공합니다.
  - 전용 예외는 [BusinessException](../../common-core/src/main/java/com/backtoback/common/exception/BusinessException.java)을 상속하고 오류 코드를 전달합니다.
  - `Retry-After` 등 부가 헤더가 필요하면 헤더를 받는 생성자를 사용합니다.
  - 헤더 값의 계산은 서비스에서 담당합니다.
- **시간 기준**
  - 업무 로직에서 Clock을 주입받으려면 [ClockConfiguration](../../common-core/src/main/java/com/backtoback/common/time/ClockConfiguration.java)을 등록합니다.
  - JPA 감사 설정을 사용하면 함께 등록되므로 별도로 추가하지 않아도 됩니다.

### 2.2 common-web — MVC·요청 추적

사용할 기능에 맞춰 등록합니다.

- MVC 오류 응답: `GlobalExceptionHandler`
- 수신 요청 추적: `CorrelationIdFilter`
- HTTP 호출의 추적 ID 전달: `HttpClientConfiguration`
- 사용자 인증: §3의 보안 체인에 연결

**MVC 예외 처리**

- [GlobalExceptionHandler](../../common-web/src/main/java/com/backtoback/common/web/exception/GlobalExceptionHandler.java)가 비즈니스 예외와 MVC 표준 예외를 처리합니다.
- MVC 호출 중 발생한 인증·권한 예외는 다시 던져 보안 체인에 전달합니다.
  - §3의 401·403 처리기도 함께 연결해야 합니다.
- DB 제약 이름별 변환·낙관적 락 충돌은 서비스에서 처리합니다.
  - 별도 advice는 공통 처리기와 충돌하지 않도록 적용 범위·우선순위를 정합니다.

**요청 추적**

- `CorrelationIdFilter`는 수신한 `X-Correlation-Id`를 MDC와 응답 헤더에 연결합니다.
  - MDC 키: `correlationId`
  - 값이 없거나 허용 형식이 아니면 UUID를 생성합니다.
  - 정상 반환 시 응답 상태, 예외 전파 시 예외 종류를 기록합니다.
  - 요청 종료 후 이전 MDC 값을 복원합니다.
- 로그 패턴에는 `%X{correlationId}`를 연결합니다.
- `HttpClientConfiguration`은 Boot의 `RestClient.Builder`에 인터셉터를 추가합니다.
  - HTTP 호출 시점의 MDC 값을 헤더로 전달합니다.
  - 직접 생성한 Builder에는 해당 customizer를 별도로 적용합니다.
- 다른 스레드·메시지로 추적 ID를 전달하려면 별도로 구성합니다.

### 2.3 common-jpa — 생성·수정 시각

[JpaAuditingConfiguration](../../common-jpa/src/main/java/com/backtoback/common/jpa/config/JpaAuditingConfiguration.java)을 등록합니다.
이 설정은 `ClockConfiguration`도 함께 등록합니다.

- **Entity 연결**
  - 생성·수정 시각을 모두 관리할 Entity는 [BaseTimeEntity](../../common-jpa/src/main/java/com/backtoback/common/jpa/entity/BaseTimeEntity.java)를 상속합니다.
  - 생성 시각은 최초 저장 시 설정합니다.
  - 수정 시각은 JPA 저장·변경 시 갱신합니다.
- **시간 기준**
  - 주입된 Clock의 현재 시점을 서울 시간으로 변환합니다.
  - 마이크로초 미만은 절삭합니다.
  - 기준은 스케줄러 §0.1과 도메인 및 데이터베이스 §5를 따릅니다.
- **설정 중복 방지**
  - 기존 `@EnableJpaAuditing`, 감사 시간 공급자·Clock을 중복 등록하지 않습니다.
  - 테스트의 고정 Clock은 공통 Clock보다 우선 주입되도록 구성합니다.
- **서비스에서 유지할 부분**
  - PK·낙관적 락 버전·삭제 상태는 서비스 Entity에서 관리합니다.
  - 기존 기반 Entity에서는 시간 필드만 공통 기반으로 옮깁니다.
- **별도 시간 처리가 필요한 경우**
  - 생성 시각만 있는 불변 테이블: `BaseTimeEntity` 적용 제외
  - 직접 SQL·벌크 UPDATE: 감사 리스너가 실행되지 않으므로 쿼리에서 시간 갱신

---

### 2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계

두 모듈의 설계 계약은 다음과 같다. 의존성 추가만으로 Relay·리스너·정리 작업을 활성화하지 않고, 서비스가 사용할 설정과 작업을 명시적으로 등록한다. 공통 모듈의 설계와 서비스 연결은 각각 구현·검증해야 한다.

- **발행:** 업무 변경과 Outbox 기록을 한 트랜잭션에 저장한다. Relay의 잠금·배치·주기·재시도와 잠금 중 원격 호출 예외는 스케줄러 §0.2.1을 따른다. 발행 성공 후 업무 상태 갱신은 서비스 확장 지점에서 조건부로 같은 발행 트랜잭션에 연결한다.
- **수신:** 공통 메타데이터 검사·SNS envelope 해제와 `eventType`·`schemaVersion`별 핸들러 연결을 제공한다. 업무 반영과 `processed_events`는 같은 트랜잭션이며 커밋 후 ACK한다. 해당 `event_id`의 소비 완료가 확인된 경우만 중복 성공으로 처리하고 일반 DB 제약 오류·업무 실패는 재전달 규칙에 맡긴다. 업무 순서 값·상태 판단은 서비스가 담당한다.
- **정리·잠금:** JDBC LockProvider와 Outbox·소비 기록 정리는 스케줄러 §0.2.2·§0.2.3을 따른다. 도메인 파일 삭제·태그 정리·업무 복구는 서비스가 소유한다. 기존 Problem ShedLock 등록과 중복되지 않도록 공통 설정을 적용하는 서비스는 기존 등록을 교체한다.
- **DB·의존성:** 각 서비스가 자기 스키마의 공통 테이블과 Flyway 버전·실행을 관리한다. 이미 실행한 SQL은 이동·재실행하지 않는다. `common-scheduling`은 `common-event`를 사용하고 반대 방향 의존은 두지 않는다. 기존 공통 응답·인증·Clock·감사를 재사용한다.

## 3. 보안 체인 연결

서비스의 `SecurityFilterChain`에 공통 인증 필터와 오류 처리기를 연결합니다.
아래 코드는 기존 체인에 추가할 설정 부분입니다.

- `http`: 서비스의 `HttpSecurity`
- `objectMapper`: 서비스가 제공하는 `tools.jackson.databind.ObjectMapper`
  - 401·403 오류 응답을 JSON으로 작성할 때 사용합니다.

```java
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.backtoback.common.web.security.RequestAuthenticationFilter;
import com.backtoback.common.web.security.SecurityAccessDeniedHandler;
import com.backtoback.common.web.security.SecurityAuthenticationEntryPoint;

http
    .sessionManagement(session -> session
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .securityContext(context -> context
        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
    .exceptionHandling(errors -> errors
        .authenticationEntryPoint(new SecurityAuthenticationEntryPoint(objectMapper))
        .accessDeniedHandler(new SecurityAccessDeniedHandler(objectMapper)))
    .addFilterAfter(new RequestAuthenticationFilter(), ExceptionTranslationFilter.class);
```

- **필터 순서·인증 유지**
  - 인증 필터는 `ExceptionTranslationFilter` 뒤·`AuthorizationFilter` 앞에 둡니다.
  - 이 순서로 인증·권한 예외를 보안 체인의 401·403 처리기에 전달합니다.
  - 인증 정보는 세션 대신 요청 속성에 저장합니다.
  - 보안 체인도 위 저장소를 사용해야 비동기 재진입 시 인증 정보를 복원할 수 있습니다.
- **사용자 헤더 처리**
  - `/api/` 요청에서 `X-User-Id`, `X-User-Role`을 읽습니다.
  - 두 헤더 모두 없음: 인증 정보를 만들지 않음
  - 일부 헤더 누락·잘못된 값: 인증 오류
  - 사용자 정보는 [GatewayUser](../../common-web/src/main/java/com/backtoback/common/web/security/user/GatewayUser.java) principal에서 조회합니다.
  - 역할은 `UserRole.USER`, `UserRole.ADMIN`을 사용합니다.
- **서비스 권한 설정**
  - 공개·로그인·관리자 경로는 서비스가 정합니다.
  - 메서드 권한 검사를 사용하면 `@EnableMethodSecurity`를 활성화합니다.
  - 리소스 소유권·업무 권한도 서비스에서 검사합니다.
- **중복 등록 방지**
  - `RequestAuthenticationFilter`를 보안 체인 밖의 Servlet 필터로 중복 등록하지 않습니다.
  - 기존 사용자 인증 필터도 함께 실행하지 않도록 정리합니다.
- **신뢰 경계**
  - Gateway가 JWT를 검증하고 외부 사용자 식별 헤더를 제거해야 합니다.
  - 백엔드 포트는 내부망으로 격리해야 합니다.
  - 공통 필터는 JWT를 다시 검증하지 않습니다.

내부 API의 인증 경계는 [서비스 간 인증 방식](../records/decisions/2026-10-14-internal-service-auth.md)의 결정을 따릅니다. 공통 사용자 인증 필터는 내부 API의 사용자 헤더를 해석하지 않습니다.

연결 예시는 [GatewaySecurityTest](../../common-web/src/test/java/com/backtoback/common/web/security/GatewaySecurityTest.java)의 `SecurityTestConfiguration`을 참고합니다.
테스트의 URL 허용 목록은 서비스에 그대로 복사하지 않습니다.

---

## 4. 적용 주의점과 확인 순서

1. 사용할 기능과 의존성을 선택합니다.
2. 기존 공통 코드의 사용처를 확인하고 공통 타입으로 연결합니다.
   - 서비스별 DTO·오류·DB 제약 처리는 유지합니다.
3. 설정 등록과 보안 체인을 연결합니다.
   - 기존 예외 처리기·인증 및 추적 필터·Clock·JPA 감사 설정의 중복을 제거합니다.
4. 응답·오류·권한·추적·감사 시각과 서비스별 동작을 확인합니다.
   - 공통 모듈 테스트 통과와 서비스 연동 확인을 구분합니다.
5. 변경된 모듈을 사용하는 서비스를 재빌드·재배포합니다.
   - 공통 소스 변경은 이미 실행 중인 서비스에 자동 반영되지 않습니다.

서비스 적용과 검증은 각 서비스의 별도 작업으로 진행합니다. 확인 항목은 다음 테스트를 참고합니다.

| 확인 대상 | 참고 테스트 |
| --- | --- |
| 응답 JSON·페이지 계약 | [ApiContractTest](../../common-core/src/test/java/com/backtoback/common/api/ApiContractTest.java) |
| MVC 오류 상태·헤더·본문 | [GlobalExceptionHandlerTest](../../common-web/src/test/java/com/backtoback/common/web/exception/GlobalExceptionHandlerTest.java) |
| 인증·권한·비동기 인증 유지 | [GatewaySecurityTest](../../common-web/src/test/java/com/backtoback/common/web/security/GatewaySecurityTest.java) |
| 요청 추적 ID·MDC 정리 | [CorrelationIdTest](../../common-web/src/test/java/com/backtoback/common/web/logging/CorrelationIdTest.java) |
| 생성·수정 시각 | [JpaAuditingTest](../../common-jpa/src/test/java/com/backtoback/common/jpa/config/JpaAuditingTest.java) |
