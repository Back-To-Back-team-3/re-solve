# Re:Solve k6 공통 부하 테스트 가이드

이 디렉터리는 Re:Solve 백엔드 서비스의 성능, 가용성, 병목 지표 측정을 위한 **k6 공통 부하 테스트 시나리오**를 포함합니다.

- **기준 문서**:
  - `docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08 (관측성 / k6 공통 시나리오)`
  - `docs/dev/planning/policy-and-state.md > 2.1 TTL 표 > 제출 Rate Limit, 11. 비기능 요구사항`
  - `docs/dev/specifications/api-spec.md > 1.1.6 개발용 로그인, 3.1.2 일반 풀이 제출, 7.1.1 알림 구독`

---

## 📁 디렉터리 구성

- `config.js`: 기본 대상 URL, 테스트 계정/문제/회차 ID, 공통 HTTP 헤더 생성 유틸리티, 비기능 요구사항 임계치(Thresholds)
- `scenarios/`:
  - `auth.js`: 개발용 로그인(`1.1.6`) 및 토큰 재발급(`1.1.4`) 부하 테스트
  - `problem-submission.js`: 문제 목록/상세 조회(`2.1`), `Idempotency-Key` 기반 일반 풀이 제출(`3.1.2`) 및 채점 상태 조회(`3.1.5`)
  - `sse-notification.js`: 실시간 SSE 알림 스트림 구독(`7.1.1`) 및 연결 유지
- `common-scenarios.js`: 3가지 워크로드를 동시 또는 환경변수(`SCENARIO`)에 따라 단독 실행하는 통합 엔트리포인트

---

## 🚀 실행 방법

### 1. k6 설치 (로컬)
```bash
# Windows (winget 또는 choco)
winget install k6 --source winget
# 또는
choco install k6

# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

### 2. 통합 부하 테스트 실행

#### 로컬 환경 실행 (전체 시나리오 동시)
```bash
k6 run k6/common-scenarios.js
```

#### 스테이징 / 운영 환경 대상 실행 (환경 변수 지정)
```bash
k6 run \
  --env BASE_URL=https://staging.re-solve.com \
  --env ACCESS_TOKEN="your-jwt-token" \
  --env TEST_PROBLEM_ID=1 \
  --env TEST_PROBLEM_REVISION_ID=1 \
  k6/common-scenarios.js
```

#### 특정 시나리오만 단독 실행 (`SCENARIO` 환경변수 활용)
```bash
# 1. 인증 시나리오만 단독 실행 (최대 20 VUs)
k6 run --env SCENARIO=auth k6/common-scenarios.js

# 2. 문제 풀이 제출 시나리오만 단독 실행 (최대 15 VUs)
k6 run --env SCENARIO=submission k6/common-scenarios.js

# 3. 실시간 SSE 알림 구독 시나리오만 단독 실행 (10 VUs)
k6 run --env SCENARIO=sse k6/common-scenarios.js
```

---

## 📊 성능 임계치 (Thresholds - policy-and-state.md 11)

- `http_req_duration`:
  - **p(95) < 1000ms**: 95%의 요청이 1초 이내에 처리되어야 함.
  - **p(99) < 2000ms**: 99%의 요청이 2초 이내에 처리되어야 함.
- `http_req_failed`:
  - **rate < 0.01**: 전체 요청 중 서버 오류율이 1% 미만이어야 함.
