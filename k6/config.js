// Re:Solve k6 공통 부하 테스트 설정 모듈
// 기준 문서:
//   - docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08
//   - docs/dev/planning/policy-and-state.md > 11. 비기능 요구사항
//   - docs/dev/specifications/api-spec.md > 0.2 식별자 규칙

export const CONFIG = {
    BASE_URL: __ENV.BASE_URL || 'http://localhost:8080',
    TEST_MEMBER_ID: __ENV.TEST_MEMBER_ID || '1',
    TEST_PROBLEM_ID: __ENV.TEST_PROBLEM_ID || '1',
    TEST_PROBLEM_REVISION_ID: __ENV.TEST_PROBLEM_REVISION_ID || '1',
    ACCESS_TOKEN: __ENV.ACCESS_TOKEN || null,
    TIMEOUT: __ENV.TIMEOUT || '10s',
};

// 공통 HTTP 헤더를 생성한다.
// VU별 고유 memberId를 할당해 동일 사용자 제출 Rate Limit(분당 10회) 충돌을 방지한다.
export function createHeaders(token = null, idempotencyKey = null, memberId = null) {
    const headers = {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
    };

    const effectiveToken = token || CONFIG.ACCESS_TOKEN;
    if (effectiveToken) {
        headers['Authorization'] = `Bearer ${effectiveToken}`;
    }

    const effectiveMemberId = memberId || (typeof __VU !== 'undefined' && __VU > 0 ? String(__VU) : String(CONFIG.TEST_MEMBER_ID));
    headers['X-User-Id'] = effectiveMemberId;
    headers['X-User-Role'] = 'USER';

    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey;
    }

    return headers;
}

// policy-and-state.md 11. 비기능 요구사항 기준 성능 임계치를 정의한다.
// 일반 API p95 <= 1000ms, 서버 에러율 < 1%를 목표로 한다.
export const COMMON_THRESHOLDS = {
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed': ['rate<0.01'],
};
