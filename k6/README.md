# Re:Solve k6 공통 부하 테스트 가이드

이 디렉터리는 Re:Solve 백엔드 서비스의 성능, 가용성, 병목 지표 측정을 위한 **k6 공통 부하 테스트 시나리오**를 포함합니다.

- **기준 문서**:
  - `docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08 (관측성 / k6 공통 시나리오)`
  - `docs/dev/planning/policy-and-state.md > 2. 시간·TTL 정책`
  - `docs/dev/specifications/api-spec.md`

---

## 📁 디렉터리 구성

- `config.js`: 기본 대상 URL, 테스트 계정/문제 ID, 공통 HTTP 헤더 생성 유틸리티, 성능 임계치(Thresholds)
- `scenarios/`:
  - `auth.js`: 개발자 로그인 및 토큰 재발급 부하 테스트
  - `problem-submission.js`: 문제 목록/상세 조회, `Idempotency-Key` 기반 풀이 제출 및 채점 상태 조회
  - `sse-notification.js`: 실시간 SSE 알림 스트림 구독 및 연결 유지
- `common-scenarios.js`: 3가지 워크로드를 동시 또는 단계별로 실행하는 통합 엔트리포인트

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

#### 로컬 환경 실행
```bash
k6 run k6/common-scenarios.js
```

#### 스테이징 / 운영 환경 대상 실행 (환경 변수 지정)
```bash
k6 run \
  --env BASE_URL=https://staging.re-solve.com \
  --env TEST_MEMBER_ID=1 \
  --env TEST_PROBLEM_ID=1 \
  k6/common-scenarios.js
```

#### 특정 시나리오만 단독 실행
```bash
# 인증 시나리오만 20 VUs로 30초 실행
k6 run --vus 20 --duration 30s -e SCENARIO=auth k6/common-scenarios.js
```

---

## 📊 성능 임계치 (Thresholds)

- `http_req_duration`:
  - **p(95) < 500ms**: 95%의 요청이 500ms 이내에 처리되어야 함.
  - **p(99) < 1500ms**: 99%의 요청이 1.5초 이내에 처리되어야 함.
- `http_req_failed`:
  - **rate < 0.01**: 전체 요청 중 에러 발생 비율이 1% 미만이어야 함.
