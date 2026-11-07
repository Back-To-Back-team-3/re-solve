// =================================================================
// Re:Solve k6 공통 부하 테스트 설정 모듈
// 기준 문서:
//   - docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08
//   - docs/dev/specifications/api-spec.md
// =================================================================

export const CONFIG = {
    BASE_URL: __ENV.BASE_URL || 'http://localhost:8080',
    TEST_MEMBER_ID: __ENV.TEST_MEMBER_ID || '1',
    TEST_PROBLEM_ID: __ENV.TEST_PROBLEM_ID || '1',
    TIMEOUT: __ENV.TIMEOUT || '10s',
};

// 공통 HTTP 헤더 생성기
export function createHeaders(token = null, idempotencyKey = null) {
    const headers = {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
    };

    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    // Gateway bypass 또는 개발 환경용 헤더
    headers['X-User-Id'] = CONFIG.TEST_MEMBER_ID;
    headers['X-User-Role'] = 'USER';

    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey;
    }

    return headers;
}

// 공통 성능 임계치 (Thresholds)
export const COMMON_THRESHOLDS = {
    // 95%의 요청이 500ms 이내에 완료되어야 함
    'http_req_duration': ['p(95)<500', 'p(99)<1500'],
    // 에러율(실패율)은 1% 미만이어야 함
    'http_req_failed': ['rate<0.01'],
};
