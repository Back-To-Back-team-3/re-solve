# Spotless & Code 컨벤션 & Build 메뉴얼

## 1. 코드 컨벤션 개요

- Naver Java Coding Convention v1.2 기반. 들여쓰기는 **스페이스 4칸**, 최대 줄 길이 120자.
    - **포맷 적용 (SSOT)**: Spotless + `config/naver-eclipse-formatter.xml`
    - **구조·네이밍 검증**: Checkstyle + `config/naver-checkstyle-rules.xml` (+ `config/naver-checkstyle-suppressions.xml`)
- IntelliJ 포맷은 근사치이며, **최종 판정은 Spotless가 한다**.
- 커밋 전 `./gradlew spotlessApply` 실행 필수.
- 들여쓰기 검사는 Checkstyle에서 제외.
    - `naver-checkstyle-suppressions.xml`에 Indentation 모듈 예외 규칙이 추가되어, 들여쓰기 판정은 전적으로 Spotless가 담당한다.
    - Checkstyle 리포트에 Indentation 위반이 나타나지 않는 것이 정상이다.

---

## 2. IntelliI 사전 설정

- Naver Java Formatter 적용
    - IntelliJ > Settings > Editor > Coding Style > Java > [Scheme: ]  톱니바퀴 > Import Scheme > [IntelliJ IDEA code style XML] > `re-solve/config/naver-intellij-formatter.xml` > Apply
- 저장 시 자동 정리
    - IntelliJ > Settings > Tools > 기능 및 저장(Actions on Save) > [Reformat code], [Import 문 최적화(Optimize imports)] 체크
- CheckStyle-IDEA 플러그인 설치
    - IntelliJ > Settings > Plugins > [CheckStyle-IDEA] 설치 >IDE 재시작
- CheckStyle 적용
    - `Checkstyle version`을 `build.gradle`의 `toolVersion`에 가급적 맞춘다.
    - IntelliJ > Settings > Tools > Checkstyle > Configuration File `+` 추가 >
        - Description: Naver Coding Convention
        - File: `re-solve/config/naver-checkstyle-rules.xml`
        - > Next > 프로퍼티 `suppressionFile`, Value: `config/naver-checkstyle-suppressions.xml` ] 지정
        - > Next > Apply

---

## 3. 신규 도메인 / 대규모 작업시 — Code 컨벤션 적용 절

```bash
### 0. 상태 확인
git status                          # 작업 중인 변경사항 없는지 확인
git switch develop
git pull origin develop

### 1. 브랜치 생성
git switch -c style/$ISSUE-$DOMAIN-code-convention
git push -u origin style/$ISSUE-$DOMAIN-code-convention  

### 2. 포매팅 적용 및 범위 정리
./gradlew spotlessApply

git status                          # 전체 도메인이 바뀐 것 확인
git add $DOMAIN-service/src/main/java/com/backtoback/$DOMAIN/
git add $DOMAIN-service/src/test/java/com/backtoback/$DOMAIN/

git restore .                       # 스테이징 안 된 나머지 도메인 되돌리기
git status                          # $DOMAIN-service만 남았는지 재확인

### 3. 최종 점검 후 1차 커밋
./gradlew spotlessCheck
./gradlew build -x test

git commit -m "style($DOMAIN): $DOMAIN 도메인 코드 컨벤션 및 자동 포매팅 적용"
git push -u origin style/$ISSUE-$DOMAIN-code-convention

### 4. Checkstyle 위반 확인
./gradlew checkstyleMain checkstyleTest
# HTML 리포트 확인
# start build/reports/checkstyle/main.html
# start build/reports/checkstyle/test.html

### 5. 수동 수정 후 2차 커밋
./gradlew spotlessApply                      # 수동 수정으로 흐트러진 포맷 재정렬
./gradlew checkstyleMain checkstyleTest      # 위반 0 확인

git status                                   # 담당 도메인 밖 수정 여부 먼저 확인

git add $DOMAIN-service/src/main/java/com/backtoback/$DOMAIN/
git add $DOMAIN-service/src/test/java/com/backtoback/$DOMAIN/
git restore .
git status

./gradlew spotlessCheck
./gradlew build -x test

git commit -m "style($DOMAIN): 네이밍 및 import 순서 컨벤션 정리"
git push -u origin style/$ISSUE-$DOMAIN-code-convention

### 6. 최종 점검
./gradlew spotlessCheck             # 포맷 통과 확인
./gradlew build -x test             # 컴파일 및 전체 check 통과 확인
git log --oneline -5                # 커밋 2개(포맷팅 / 네이밍정리)로 분리됐는지 확인
```

**자주 나오는 위반 유형**

| 규칙 | 의미 | 수정 예시 |
| --- | --- | --- |
| `[import-grouping]` | import 그룹(`#`(static) → `java` → `javax` → `org` → `net` → `com` → 그 외 → `com.nhncorp` → `com.navercorp` → `com.naver`) 순서·빈 줄 위반 | 그룹별로 재정렬 + 그룹 사이 빈 줄 |
| `[avoid-star-import]` | `import xxx.*` 형태 금지 | 실제 쓰는 이름으로 개별 import |
| `[need-braces]` | 한 줄 `if`에 중괄호 누락 | `if (x) y;` → `if (x) { y; }` |
| `[var-lower-camelcase]` / `[avoid-1-char-var]` | 1글자 변수명 금지 | `s` → `memberId` (파라미터명과 겹치지 않게 주의) |
| `[space-around-brace]` | 빈 중괄호 앞뒤 공백 누락 | `{}` → `{ }` (줄바꿈) |
| `[line-length-120]` | 120자 초과 | 문자열 `+` 연결로 줄바꿈 |
| `[braces-knr-style]` | 닫는 중괄호가 단독 줄에 있지 않음 | K&R 스타일로 정리 | 

- `[space-around-brace]`
    - `{}` → 한 칸 공백(`{ }`)은 Spotless가 다시 축소시켜 재발한다.
    - 아래처럼 줄바꿈해서 빈 몸통으로 만들어야 통과한다.
        ```bash
        public interface SomeRepository extends JpaRepository<SomeEntity, Long> {
              
        }
        ```

**충돌 대비**

```bash
git fetch origin
git rebase origin/develop
```

---

## 4. 평소 개발 시 — 매 커밋 전 루틴

**작업 중 반복 확인용**

```bash
./gradlew spotlessApply
./gradlew compileJava
./gradlew compileTestJava
```

**신규 파일을 만들었거나 기존 파일을 크게 고쳤을 때** (선택이지만 권장)

```bash
./gradlew checkstyleMain checkstyleTest
```

**커밋/PR 올리기 전 최종 확인용 (필수)**

```bash
./gradlew spotlessCheck
./gradlew clean test
```

**CI에서 포맷 검사가 실패한 경우**

```bash
./gradlew spotlessApply
git add -A
git commit -m "style($DOMAIN): spotlessApply 적용"
git push origin <branch>
```

---

## 5. 주석 작성

주석에는 코드만 읽어서는 알 수 없는 이유·계약·제약을 적는다. 코드가 하는 일을 다시 설명하는 주석은 쓰지 않는다. 이 기준이 생기기 전에 작성한 주석은 해당 파일을 수정할 때 맞추고, 주석만 고치는 커밋을 따로 만들지 않는다.

### 5.1 공통

- 한국어로 쓰고 문장은 `~한다.`로 끝낸다. record·DTO처럼 데이터를 나타내는 타입의 요약은 명사구로 끝내도 된다.
- 기준 문서를 근거로 한 코드는 근거 문서와 번호를 함께 적는다. 행 번호는 적지 않는다.
    - 예: `(API 명세서 1.1.4)`, `(ERD 4.4.1 exams)`, `(정책 및 상태 §1 검증 책임)`
- Java Javadoc 안의 식별자·코드 값은 `{@code}`로 감싼다. TypeScript는 JSDoc(`/** */`)을 쓰고 같은 기준을 적용한다.

### 5.2 문서 주석 (`/** */`)

| 대상 | 적는 내용 |
| --- | --- |
| 클래스·인터페이스·record | 책임을 한두 문장으로 적는다. 다른 서비스와 나눈 경계(이 타입이 하지 않는 일)가 있으면 함께 적는다. |
| Controller 메서드 | API 명세서 번호와 이름, 이름만으로 드러나지 않는 처리를 적는다. 예: `1.1.4 토큰 재발급 — Refresh Token을 교체하고 새 Access Token을 내려준다.` |
| public 메서드 | 이름만으로 드러나지 않는 실패 처리·부수 효과·멱등성·동시성 보장이 있을 때만 적는다. 실패하면 던지는 에러 코드를 적는다. |
| getter·단순 위임·`main`·이름으로 충분한 메서드 | 쓰지 않는다. |

- `@param`·`@return`은 이름과 타입만으로 의미가 드러나지 않을 때만 쓴다.

### 5.3 한 줄 주석 (`//`)

- 그렇게 구현한 이유만 적는다. 정책 근거, 외부 시스템의 특이 동작, 동시성·멱등성 처리, 의도적으로 무시한 예외가 대상이다.
    - 예: `// GitHub은 잘못된 코드에도 200과 error 필드를 돌려준다.`
    - 예: `// 검증 예외에는 입력값이 포함될 수 있으므로 예외 종류만 기록한다.`
- 코드 한 줄마다 동작을 풀어 쓰지 않는다.

### 5.4 쓰지 않는 것

- Issue 번호·"이번 Issue에서는"·"후속 작업"·"임시" 같은 진행 상태. 병합 후 바로 낡으므로 남은 작업은 Issue로 만든다.
    - 남겨야 하는 미완성 지점만 `// TODO(#Issue번호): 내용` 형식으로 쓰고, 해당 Issue가 닫힐 때 함께 지운다.
- 작성자·작성일·변경 이력. Git이 기록한다.
- 주석 처리한 코드.
- 비밀값과 실제 사용자 데이터.

### 5.5 테스트

- 테스트 메서드에는 `@DisplayName`으로 검증하는 조건과 결과를 한국어 문장으로 적는다.
    - 예: `@DisplayName("기본 이메일이 확인되지 않았으면 이메일 없이 가입한다")`
- 준비·실행·검증 단계가 길어 구분이 필요하면 `// given`, `// when`, `// then`으로 나눈다.

---

## 6. 코드 구조

- `api-gateway`를 제외한 서비스에 적용한다. 계약(응답 형식·에러 코드·테이블·마이그레이션 파일명)은 명세가 원본이고, 이 절은 그 계약을 담는 코드의 배치와 이름만 정한다.
- 채택 근거는 `docs/records/decisions/`의 백엔드 코드 구조 결정 기록에 있다.

### 6.1 패키지

```
com.backtoback.{서비스}
├── {기능}/              # 기획서 2.1 도메인의 패키지 이름 (예: auth, member, exam, submission)
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/
│   ├── dto/
│   ├── client/          # 다른 서비스·외부 API 호출
│   ├── store/           # Redis 등 JPA가 아닌 저장소
│   └── config/          # 기능 전용 설정
└── global/
    ├── config/
    └── error/           # 서비스 에러 코드 enum
```

- 하위 패키지는 그 종류의 클래스가 생길 때 만든다.
- 관리자 API는 기획서 2.1 도메인에 따라 기능 패키지 `admin`에 둔다.
- 공통 모듈(6.2)에 있는 클래스는 `global`에 다시 만들지 않는다.

### 6.2 공통 모듈

| 패키지 (`com.backtoback.common`) | 클래스 | 역할 |
| --- | --- | --- |
| `api` | `ApiResponse`, `PageResponse`, `ApiErrorResponse` | API 명세서 0.3 공통 응답 |
| `exception` | `ErrorCode`(인터페이스), `CommonErrorCode`, `BusinessException` | 공통 오류 처리 |
| `web.exception` | `GlobalExceptionHandler` | 공통 예외 핸들러 |
| `jpa.entity` | `BaseTimeEntity` | `created_at`·`updated_at` |

- 서비스는 위 클래스와 같은 역할의 클래스를 만들지 않는다.
- 공통 모듈에 클래스를 추가하려면 결정 기록을 먼저 고친다.

### 6.3 응답·예외

- Controller는 `ResponseEntity<ApiResponse<T>>`를 반환하고, 공통 응답을 적용하지 않는 경우는 API 명세서 0.3을 따른다.
- 업무 오류는 공통 `BusinessException`을 상속한 서비스별 예외 하나에 서비스 에러 코드를 전달해 던진다. 오류마다 예외 클래스를 만들지 않는다.
- 서비스 에러 코드는 `global/error/{서비스}ErrorCode` enum 하나에 두고 `ErrorCode`를 구현한다. 이름·HTTP 상태는 에러 코드 표를 따르고, 표에 없는 코드는 문서를 먼저 고친다(Git/GitHub 메뉴얼 1.7).
- Controller에서 예외를 잡아 응답을 만들지 않는다. 브라우저가 직접 여는 리다이렉트처럼 공통 오류 형식으로 응답할 수 없는 API만 예외로 하고, 이유를 주석으로 적는다.

### 6.4 DTO

- 요청·응답 DTO는 record로 `dto`에 두고, 이름은 `{대상}{동작}Request`, `{대상}Response`로 짓는다.
- 요청 검증은 Bean Validation 어노테이션과 한국어 `message`로 하고, Controller에서 `@Valid`를 붙인다.
- 서비스 결과를 응답으로 바꾸는 일은 응답 DTO의 `static from(...)`이 맡는다. 엔티티를 응답으로 반환하지 않는다.
- 서비스 계층끼리 주고받는 결과 record는 `service`에 둔다.

### 6.5 엔티티

- `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 붙이고 `@Setter`·`@Builder`는 쓰지 않는다.
- 생성은 `private` 생성자와 의도가 드러나는 이름의 정적 팩토리(`registerWithGitHub` 등)로 한다.
- 상태 판단·변경은 엔티티 메서드로 한다(정책 및 상태 9.4 운영·보안 정책 > 상태 변경).
- 생성·수정 시간을 모두 관리하는 엔티티는 `BaseTimeEntity`를 상속한다. 생성 시간만 있는 불변 테이블은 공통 모듈 적용 가이드 2.3의 별도 시간 처리를 따른다. 
  - 컬럼·제약은 ERD, 마이그레이션 파일명은 ERD 7. 마이그레이션 파일 목록을 따른다.

### 6.6 서비스·설정

- 의존성은 `private final` 필드와 `@RequiredArgsConstructor`로 주입한다.
- `@Transactional`은 public 메서드에 붙이고, 조회 메서드는 `readOnly = true`로 한다. 클래스에는 붙이지 않는다. 트랜잭션을 걸지 않는 public 쓰기 메서드에는 이유를 주석으로 적는다.
- 현재 시각은 주입한 `Clock`으로 구한다.
- 설정 값은 `@ConfigurationProperties` record와 `@Validated`로 묶는다. `@Value`는 쓰지 않는다.

### 6.7 테스트

- 테스트는 대상과 같은 패키지에 `{대상}Test`로 둔다.
- 단위 테스트는 `MockitoExtension`, BDDMockito `given`, AssertJ로 작성한다.
- Controller 테스트는 `@WebMvcTest`와 `@MockitoBean`으로 작성한다.
- DB·Redis가 필요한 테스트는 Testcontainers를 쓰고, Docker가 없으면 `@Testcontainers(disabledWithoutDocker = true)`로 건너뛴다.
- 테스트 메서드 주석은 5.5 테스트를 따른다.
- Spring Boot 컨텍스트 로드 테스트는 `@SpringBootTest` + Testcontainers MySQL로 작성한다.
- Controller 테스트는 `@WebMvcTest`와 `@MockitoBean`으로 작성한다. 단, Application 클래스에 `@Import(JpaAuditingConfiguration)`이 있으면 `@WebMvcTest`가 JPA metamodel 오류로 실패한다. 이 경우 `@SpringBootTest`를 쓰고 PR 리뷰어에게 이유를 명시한다.
- 프로필별로 다른 컨텍스트가 필요하면 클래스를 나눈다. 예: `SecurityConfigurationTest`(기본), `SecurityConfigurationLocalProfileTest`(local)
- 기존 클래스명이 `…Tests`인 경우 그대로 유지한다.

---

end.
