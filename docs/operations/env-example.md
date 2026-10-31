# .env.example / docker

> 환경 변수 파일 / docker 실행 메뉴얼
> 

## 1. 환경 변수 파일 생성 (.env)

```
# =================================================================
# Re:Solve — 로컬 / Docker 환경 변수 예시
# .env.example을 .env로 복사한 뒤 비밀번호를 채운다.
# =================================================================

# 🐳 Docker 이미지와 실행 환경

# Compose 프로젝트명과 로컬 frontend 실행 프로필
COMPOSE_PROJECT_NAME=re-solve
COMPOSE_PROFILES=local

# 로컬에서 빌드한 백엔드 이미지 이름에 사용
DOCKERHUB_USERNAME=local-re-solve
IMAGE_TAG=local

# 백엔드 Spring 프로필
SPRING_PROFILES_ACTIVE=local

# 🐬 MySQL
MYSQL_IMAGE=mysql:8.4

# PC에서 접속할 포트 (컨테이너 내부 포트는 3306)
MYSQL_HOST_PORT=3308

# 필수 값: MySQL 관리자 비밀번호와 서비스용 DB 계정
MYSQL_ROOT_PASSWORD=
DB_USERNAME=
DB_PASSWORD=

# Compose 내부의 MySQL 접속 정보
DB_HOST=mysql
DB_PORT=3306

# 현재 mysql-init이 생성하는 서비스별 DB 이름
MEMBER_DB_NAME=member_db
PROBLEM_DB_NAME=problem_db
JUDGE_DB_NAME=judge_db
CONTEST_DB_NAME=contest_db
STUDY_DB_NAME=study_db
NOTIFICATION_DB_NAME=notification_db
INTEGRATION_DB_NAME=integration_db
AI_DB_NAME=ai_db
ANALYTICS_DB_NAME=analytics_db

# 🔴 Redis
REDIS_IMAGE=redis:7.4-alpine

# PC에서 접속할 포트 (컨테이너 내부 포트는 6379)
REDIS_HOST_PORT=6380

# 필수 값: Redis 비밀번호
REDIS_PASSWORD=

# Compose 내부의 Redis 접속 정보
REDIS_HOST=redis
REDIS_PORT=6379

# 🔗 연동 (integration-service)
# 필수 값: GitHub Access Token 암호화 키 (AES-256 GCM). Base64로 인코딩된 32바이트 키
# 예: openssl rand -base64 32
INTEGRATION_CRYPTO_SECRET_KEY=
GITHUB_APP_ID=
GITHUB_APP_SLUG=resolve-sync
GITHUB_APP_PRIVATE_KEY=

# ☁️ AWS / Judge0 / LocalStack
LOCALSTACK_IMAGE=localstack/localstack:3.8
LOCALSTACK_HOST_PORT=4566
LOCALSTACK_ENDPOINT=http://localstack:4566
AWS_REGION=ap-northeast-2
AWS_ENDPOINT_URL=
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=

# ⚖️ Judge0 (채점 엔진 - Docker Compose 프로필: judge0)
# Compose 컨테이너 환경(기본값): http://judge0-server:2358
# 호스트 로컬 IDE에서 직접 실행 시: http://localhost:2358
# 언어 ID와 런타임은 실행 중인 Judge0 서버에서 확인한다.
JUDGE0_IMAGE=judge0/judge0:1.13.1-extra
JUDGE0_SERVER_PORT=2358
JUDGE0_BASE_URL=http://judge0-server:2358
JUDGE0_AUTH_HEADER=X-Auth-Token
JUDGE0_AUTH_TOKEN=
JUDGE0_CONNECT_TIMEOUT=2s
JUDGE0_READ_TIMEOUT=5s
# ID·런타임은 Judge0에서 조회. 실제 실행 검증 후 해당 언어를 활성화
JUDGE0_JAVA_ENABLED=false
JUDGE0_PYTHON3_ENABLED=false
JUDGE0_CPP_ENABLED=false

# 🌐 Nginx
NGINX_IMAGE=nginx:stable-alpine

# 로컬 PC에서 접근할 주소와 포트
NGINX_BIND_ADDRESS=127.0.0.1
NGINX_HTTP_PORT=8080

# 포트는 매핑돼 있지만 현재 로컬 설정에는 HTTPS 리스너가 없음
NGINX_HTTPS_PORT=8443

# 로컬 Nginx 설정과 마운트 경로
NGINX_CONFIG_FILE=./nginx/local.conf
NGINX_CERTS_DIR=./nginx
NGINX_CHALLENGE_DIR=./nginx
```

---

## 2. 값 입력 시 주의 사항

- 필수 값이 비어 있으면 Compose가 시작을 거절한다. 관리자 비밀번호와 서비스 계정 비밀번호를 같게 맞출 필요는 없다.
- 첫 실행에서 `mysql-init`이 현재 9개 서비스 DB를 만들고 개발 계정에 권한을 부여한다.
- 실제 비밀번호·접속 주소·토큰은 GitHub에 올리면 안되고 Issue, PR에도 적지 않는다.

### Judge 검증 수신·실행·결과 이벤트 연결

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `JUDGE_MESSAGING_ENABLED` | `false` | 검증 SQS 수신·작업 실행·기한 초과 종결·결과 SNS Outbox Relay를 함께 활성화 |
| `JUDGE_EVENTS_TOPIC_ARN` | 빈 값 | 활성화 시 필수. 기존 `judge-events` Standard SNS 토픽 ARN |
| `PROBLEM_VALIDATION_QUEUE_URL` | 빈 값 | 활성화 시 필수. 기존 `problem-validation-queue` Standard SQS 큐 URL |

- 비활성 상태에서도 검증 저장 테이블·공통 소비기·Outbox 기록기·진행 조회 API는 유지한다. SQS 수신·자동 작업 실행·기한 초과 정리·결과 발행은 모두 중지한다. 환경변수를 바꾼 뒤 서비스를 재시작해 적용한다.
- 활성화 전에 담당 A가 제공한 큐·토픽·구독 필터·DLQ·권한을 확인한다. 리스너는 없는 큐를 자동 생성하지 않는다.
- 검증 요청은 DB 커밋 후 수동 ACK하며 ACK 배치를 끈다. 결과는 업무 트랜잭션의 Outbox 기록 후 공통 Relay로 발행한다.
- AWS 리전·자격 증명·엔드포인트는 기존 설정을 사용한다. 로컬은 LocalStack, 운영은 서비스 실행 환경의 자격 증명 체인을 사용한다.
- IDE 실행 시 환경변수를 전달한다. 현재 Compose에는 위 세 변수의 전달 설정이 없으므로 `.env`만 수정해서는 활성화되지 않는다. Compose·운영 배포의 변수 전달과 리소스 준비는 담당 A와 연결한다.
- 활성화하면 기존 대기 작업도 실행되므로 Problem 검증 자료 API와 Judge0 주소·사용 언어 활성화 설정을 함께 준비한다. 별도의 실행기 ON/OFF 환경변수는 사용하지 않는다.
- 실행 동시성·대기열 조회·기한 초과 종결 기준은 정책 및 상태 §3.5를 따른다. 비활성 기간에도 작업 기한을 연장하지 않으며, 다시 활성화했을 때 기한이 지난 작업은 실행하지 않고 종결한다.

### Judge0 연결 설정

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `JUDGE0_IMAGE` | `judge0/judge0:1.13.1-extra` | Judge0 서버와 worker가 사용하는 이미지 |
| `JUDGE0_SERVER_PORT` | `2358` | 호스트의 로컬 인터페이스에 공개할 Judge0 API 포트 |
| `JUDGE0_BASE_URL` | 실행 환경별 | Judge0 서버 주소. 같은 Docker 네트워크의 서버는 해당 서비스명으로 연결하고, 호스트에서 실행하는 judge-service는 호스트에 공개된 주소로 연결한다. |
| `JUDGE0_AUTH_HEADER` | `X-Auth-Token` | Judge0 서버가 요구하는 인증 헤더 이름 |
| `JUDGE0_AUTH_TOKEN` | 빈 값 | 인증 토큰. 빈 값이면 인증 헤더를 추가하지 않는다. |
| `JUDGE0_CONNECT_TIMEOUT` | `2s` | HTTP 연결 제한 시간 |
| `JUDGE0_READ_TIMEOUT` | `5s` | HTTP 응답 대기 제한 시간 |
| `JUDGE0_JAVA_ENABLED` | `false` | Java 실행 환경 검증 후 활성화할 언어 설정 |
| `JUDGE0_PYTHON3_ENABLED` | `false` | Python 3 실행 환경 검증 후 활성화할 언어 설정 |
| `JUDGE0_CPP_ENABLED` | `false` | C++17 실행 환경 검증 후 활성화할 언어 설정 |

- 공통 YAML은 주소를 미설정 상태로 두고, 로컬 프로필은 `http://localhost:2358`, Compose는 `http://judge0-server:2358`을 기본으로 사용한다. `JUDGE0_BASE_URL`을 지정하면 해당 값이 우선하므로 로컬 IDE에서 실행할 때는 호스트 주소로 지정한다.
- Judge0가 같은 Docker 네트워크에 `judge0-server`라는 서비스명으로 실행 중이면 주소는 `http://judge0-server:2358`이다. 해당 포트를 호스트의 2358로 공개한 경우 로컬 IDE에서 실행하는 judge-service는 `http://localhost:2358`로 연결한다.
- 연결·응답 제한에는 `2s`, `1500ms`처럼 단위를 포함한 값을 사용한다. 허용 범위는 각각 1ms 이상 2,147,483,647ms 이하이며 잘못된 값은 애플리케이션 시작 시 거부한다. 이 값은 제출 코드의 CPU 실행 시간 제한과 별개다.
- Judge0 서버의 인증 설정에 맞춰 헤더 이름과 토큰을 지정한다. 실제 토큰은 로컬 `.env`에만 입력한다.
- 언어 활성화 설정은 실행 환경 검증 후에만 `true`로 바꾼다. Judge0의 언어 ID와 런타임 버전은 배포 환경에서 조회·확인한다.
- 언어 ID·버전 목록은 최초 조회에 성공하면 서비스 메모리에 캐시하며, 조회 실패는 캐시하지 않는다. 언어 활성화는 서비스 전체 설정이며 개별 문제의 언어별 검증 상태와는 별개다.
- 설정 변경은 judge-service 재시작 후 반영한다. Compose의 `.env`를 변경했다면 컨테이너를 재생성해야 하며, 단순 `restart`로 새 환경변수가 전달되지는 않는다.

---

## 3. 컨테이너 빌드 및 가동

- `../../Dockerfile`은 각 모듈의 실행 JAR를 복사한다.s

```bash
# 저장소 루트에서 JAR 생성 후 컨테이너 빌드·실행
./gradlew bootJar
docker compose up -d --build

# 실행 상태 확인
docker compose ps
```

- `mysql-init`은 DB 생성이 끝나면 정상 종료되는 일회성 컨테이너다.
- 로컬 화면은 HTTP `http://127.0.0.1:8080/`에서 연다.

```bash
# 특정 서비스 로그 확인 (예: api-gateway)
docker compose logs -f api-gateway
```

- 현재 저장소의 frontend는 연결 확인용 화면이고, 백엔드 기능은 개발 단계다.
- 위 명령은 로컬 구성 안내이며 전체 기능의 동작 검증을 뜻하지 않는다.

---

## 4. Redis 동작 확인

- Redis 컨테이너에는 `.env`의 비밀번호가 전달된다. 명령에 비밀번호를 직접 적지 않는다.

```bash
# Redis 상태와 응답 확인
docker compose ps redis
docker compose exec redis redis-cli ping
```

- 정상 응답은 `PONG`이다.

```bash
# Redis 오류가 있으면 로그 확인
docker compose logs redis
```

---

## 5. Judge0 (채점 엔진) 가동 및 확인

Judge0는 Compose 프로필(`judge0`)로 분리되어 있다. 일반 `docker compose up`에는 포함되지 않는다.

### 5.1 가동과 언어 목록 확인

```bash
# Judge0 서비스 스택 시작과 상태 확인
docker compose --profile judge0 up -d
docker compose --profile judge0 ps

# 호스트에서 실행하는 curl·Gradle에는 호스트 주소를 사용한다.
# JUDGE0_SERVER_PORT를 바꿨다면 해당 호스트 포트로 변경한다.
export JUDGE0_BASE_URL=http://localhost:2358

# 인증 서버라면 먼저 현재 셸에 JUDGE0_AUTH_TOKEN을 설정한다.
judge0_curl_headers=()
if [ -n "${JUDGE0_AUTH_TOKEN:-}" ]; then
  judge0_curl_headers=(-H "X-Auth-Token: ${JUDGE0_AUTH_TOKEN}")
fi
curl --fail --silent --show-error "${judge0_curl_headers[@]}" "$JUDGE0_BASE_URL/system_info"
curl --fail --silent --show-error "${judge0_curl_headers[@]}" "$JUDGE0_BASE_URL/languages/"
```

언어 ID·버전은 설치한 Judge0의 응답으로 확인한다. 특정 ID가 항상 Java 17이라고 가정하지 않는다. Java는 JDK·OpenJDK 17, Python은 Python 3, C++은 GCC 기반 후보를 선택하며 C++ 제출에는 `-std=c++17`을 전달한다. 지원 후보가 없으면 다른 언어·주요 버전으로 대체하지 않는다.

### 5.2 실제 실행 접수·조회 검증

저장소 루트에서 실행한다. `.env` 파일은 이 Gradle 테스트에 자동으로 로드되지 않으므로 `JUDGE0_BASE_URL`과 필요한 `JUDGE0_AUTH_TOKEN`을 현재 셸의 환경변수로 전달한다.

```bash
# 각 명령은 해당 언어의 독립 테스트 코드를 실제 Judge0에 한 번 제출한다.
./gradlew :judge-service:judge0LiveTest -Pjudge0Language=java
./gradlew :judge-service:judge0LiveTest -Pjudge0Language=python3
./gradlew :judge-service:judge0LiveTest -Pjudge0Language=cpp
```

- 언어를 생략하면 `python3`이다. 테스트는 `/languages/`에서 실행 환경을 선택하고, `1 2`의 합이 `3`인지 같은 token의 결과를 조회해 확인한다. ID를 직접 지정하지 않는다.
- 이 테스트는 `judge0-live` 태그를 사용하며 일반 `test`·`check`·`build`에서는 제외한다. DB·Redis·LocalStack이나 Spring 애플리케이션을 실행할 필요가 없다.
- 테스트 전용 설정은 인증 헤더 `X-Auth-Token`, 연결 제한 `2s`, 응답 대기 제한 `5s`다. 애플리케이션용 `JUDGE0_AUTH_HEADER`·`JUDGE0_CONNECT_TIMEOUT`·`JUDGE0_READ_TIMEOUT`은 이 테스트에서 읽지 않는다. 다른 헤더를 쓰는 서버는 현재 테스트 설정과 맞춰야 한다.
- 테스트 내부에서 선택한 언어만 활성화한다. 서비스의 `JUDGE0_*_ENABLED` 값을 바꾸지는 않는다. 실제 검증이 끝난 언어만 서비스 설정에서 활성화하고 judge-service를 재시작한다. Compose 환경변수를 바꿨다면 컨테이너를 재생성한다.
- 실행 접수는 자동 재시도하지 않는다. 완료 조회는 최대 약 60초 동안 수행하며 테스트 전체 제한은 90초다. 실행 기록이 실제 서버에 남고 리소스를 사용한다.
- 이 테스트는 원시 코드의 접수·결과 조회를 검증한다. [함수 실행 코드](../guides/judge-execution.md)의 모든 시나리오를 실제 Judge0에서 검증했다는 뜻은 아니다.

### 5.3 Judge0만 종료·정리

```bash
# 기타 MySQL, Redis 등 서비스는 유지한다.
docker compose stop judge0-server judge0-worker judge0-db judge0-redis

# 필요할 때만 Judge0 컨테이너와 연결된 익명 볼륨을 제거한다.
docker compose rm -f -s -v judge0-server judge0-worker judge0-db judge0-redis
```

---

## 6. 종료 / 정리

```bash
# 일반 컨테이너만 종료: MySQL·Redis 데이터는 유지
docker compose down

# Judge0 포함 전체 종료
docker compose --profile judge0 down
```

- DB·Redis 데이터를 완전히 초기화해야 할 때만 사용한다. 삭제한 데이터는 복구되지 않는다.

```bash
# 컨테이너와 볼륨 삭제
docker compose down --volumes
```

---

end.
