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

# ☁️ AWS / Judge0
AWS_REGION=ap-northeast-2

# Judge0 연동 주소가 정해지면 입력
JUDGE0_BASE_URL=

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

---

## 3. 컨테이너 빌드 및 가동

- `Dockerfile`은 각 모듈의 실행 JAR를 복사한다.

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

## 5. 종료 / 정리

```bash
# 컨테이너만 종료: MySQL·Redis 데이터는 유지
docker compose down
```

- DB·Redis 데이터를 완전히 초기화해야 할 때만 사용한다. 삭제한 데이터는 복구되지 않는다.

```bash
# 컨테이너와 볼륨 삭제
docker compose down --volumes
```

---

end.