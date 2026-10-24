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

- Judge0는 리소스 관리를 위해 Compose 프로필(`judge0`)로 분리되어 있습니다.
- 일반 `docker compose up` 명령 시에는 기동되지 않으며, `--profile judge0` 플래그를 붙여 실행합니다.

```bash
# Judge0 서비스 스택 시작
docker compose --profile judge0 up -d

# Judge0 구동 상태 확인
docker compose --profile judge0 ps

# Judge0 API 응답 확인 (상태 정보)
curl http://localhost:2358/system_info

# Java 17 (JDK 17.0.6, language_id=91) 채점 테스트
curl -X POST "http://localhost:2358/submissions?base64_encoded=false&wait=true" \
     -H "Content-Type: application/json" \
     -d '{
       "source_code": "public class Main { public static void main(String[] args) { System.out.println(\"Java 17 OK\"); } }",
       "language_id": 91
     }'

# Judge0 서비스만 종료 (기타 MySQL, Redis 등 서비스 유지)
docker compose stop judge0-server judge0-worker judge0-db judge0-redis

# Judge0 컨테이너 및 볼륨 제거 (필요 시)
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
