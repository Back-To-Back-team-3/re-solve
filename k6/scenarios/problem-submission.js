// 시나리오 2: 문제 조회 및 일반 풀이 제출 (PRACTICE) 부하 테스트
// 기준 문서:
//   - docs/dev/specifications/api-spec.md > 2.1 문제 조회, 3.1.2 일반 풀이 제출, 3.1.3 제출 상태·결과 조회
//   - docs/dev/planning/policy-and-state.md > 2.1 TTL 표 > 제출 Rate Limit, 3.1 제출 > Java 제출 코드 조건

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, createHeaders } from '../config.js';
import { obtainDevToken } from './auth.js';

// VU 로컬 캐시: 각 VU 인스턴스마다 최초 1회 개발용 로그인을 수행해 토큰을 재사용한다.
let cachedToken = null;

export function testProblemSubmissionScenario() {
    const memberId = typeof __VU !== 'undefined' && __VU > 0 ? String(__VU) : String(CONFIG.TEST_MEMBER_ID);

    // VU별 유효한 JWT 토큰을 취득하여 게이트웨이 인증 통과 및 동일 회원 Rate Limit 충돌을 방지한다.
    if (!cachedToken && !CONFIG.ACCESS_TOKEN) {
        cachedToken = obtainDevToken(memberId);
    }
    const effectiveToken = CONFIG.ACCESS_TOKEN || cachedToken;

    // 1. 문제 목록 조회 (api-spec.md > 2.1.1)
    const listRes = http.get(
        `${CONFIG.BASE_URL}/api/v1/problems?page=0&size=10`,
        {
            headers: createHeaders(effectiveToken, null, memberId),
            tags: { is_api: 'true', name: 'problem_list' },
        }
    );

    check(listRes, {
        '문제 목록 조회 성공 (200 OK)': (r) => r.status === 200,
    });

    sleep(1);

    // 2. 문제 상세 조회 (api-spec.md > 2.1.2)
    const detailRes = http.get(
        `${CONFIG.BASE_URL}/api/v1/problems/${CONFIG.TEST_PROBLEM_ID}`,
        {
            headers: createHeaders(effectiveToken, null, memberId),
            tags: { is_api: 'true', name: 'problem_detail' },
        }
    );

    check(detailRes, {
        '문제 상세 조회 성공 (200 OK)': (r) => r.status === 200,
    });

    // 문제 상세 응답에서 최신 problemRevisionId를 추출한다.
    let problemRevisionId = String(CONFIG.TEST_PROBLEM_REVISION_ID);
    try {
        const detailBody = JSON.parse(detailRes.body);
        if (detailBody.data && (detailBody.data.problemRevisionId || detailBody.data.latestRevisionId)) {
            problemRevisionId = String(detailBody.data.problemRevisionId || detailBody.data.latestRevisionId);
        }
    } catch (e) {
        // 파싱 실패 시 기본 회차 ID를 유지한다.
    }

    sleep(1);

    // 3. 일반 풀이 제출 (api-spec.md > 3.1.2 POST /api/v1/submissions)
    // Idempotency-Key 필수, 사용자당 분당 10회 제한 준수
    const vuNumber = typeof __VU !== 'undefined' ? __VU : 1;
    const iterNumber = typeof __ITER !== 'undefined' ? __ITER : 0;
    const idempotencyKey = `k6-${vuNumber}-${iterNumber}-${Date.now()}`;

    // policy-and-state.md 3.1 조건에 부합하는 Java 솔루션 클래스 코드
    const javaSolutionCode = `class Solution {\n    public int solution(int a, int b) {\n        return a + b;\n    }\n}\n`;

    const submissionPayload = JSON.stringify({
        problemId: String(CONFIG.TEST_PROBLEM_ID),
        problemRevisionId: String(problemRevisionId),
        language: 'java',
        sourceCode: javaSolutionCode,
    });

    const submitRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/submissions`,
        submissionPayload,
        {
            headers: createHeaders(effectiveToken, idempotencyKey, memberId),
            tags: { is_api: 'true', name: 'submission_post' },
        }
    );

    const submitSuccess = check(submitRes, {
        '풀이 제출 접수 성공 (202 Accepted 또는 200 OK)': (r) => r.status === 202 || r.status === 200,
    });

    // 4. 채점 결과 확인 (제출 접수 성공 시 3.1.3 제출 상태·결과 조회)
    if (submitSuccess) {
        try {
            const body = JSON.parse(submitRes.body);
            const submissionId = body.data?.submissionId || body.data?.id;
            if (submissionId) {
                sleep(2);
                const statusRes = http.get(
                    `${CONFIG.BASE_URL}/api/v1/submissions/${submissionId}`,
                    {
                        headers: createHeaders(effectiveToken, null, memberId),
                        tags: { is_api: 'true', name: 'submission_status' },
                    }
                );
                check(statusRes, {
                    '채점 상태 조회 성공 (200 OK)': (r) => r.status === 200,
                });
            }
        } catch (e) {
            // JSON 파싱 실패 시 무시한다.
        }
    }

    // 동일 문제 5초 간격 및 사용자당 분당 10회 제한(최소 6초 간격)을 준수하도록 추가 대기한다.
    sleep(4);
}
