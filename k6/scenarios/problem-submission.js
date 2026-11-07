// =================================================================
// 시나리오 2: 문제 조회 및 풀이 제출 (PRACTICE) 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 2. 문제 / 3. 채점
// =================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, createHeaders } from '../config.js';

export function testProblemSubmissionScenario() {
    // 1. 문제 목록 조회 (api-spec.md > 2.1.1)
    const listRes = http.get(
        `${CONFIG.BASE_URL}/api/v1/problems?page=0&size=10`,
        { headers: createHeaders() }
    );

    check(listRes, {
        '문제 목록 조회 성공 (200 OK)': (r) => r.status === 200,
    });

    sleep(1);

    // 2. 문제 상세 조회 (api-spec.md > 2.1.2)
    const detailRes = http.get(
        `${CONFIG.BASE_URL}/api/v1/problems/${CONFIG.TEST_PROBLEM_ID}`,
        { headers: createHeaders() }
    );

    check(detailRes, {
        '문제 상세 조회 성공 (200 OK)': (r) => r.status === 200,
    });

    sleep(1);

    // 3. 문제 풀이 제출 (api-spec.md > 3.1.1 POST /api/v1/submissions)
    // Idempotency-Key 필수, Rate Limit: 분당 10회
    const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;
    const submissionPayload = JSON.stringify({
        problemId: parseInt(CONFIG.TEST_PROBLEM_ID, 10),
        language: 'java',
        code: `public class Solution { public static void main(String[] args) { System.out.println("Hello k6: ${idempotencyKey}"); } }`,
    });

    const submitRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/submissions`,
        submissionPayload,
        { headers: createHeaders(null, idempotencyKey) }
    );

    const submitSuccess = check(submitRes, {
        '풀이 제출 접수 성공 (202 Accepted 또는 200 OK)': (r) => r.status === 202 || r.status === 200,
    });

    // 4. 채점 결과 확인 (제출 접수 성공 시)
    if (submitSuccess) {
        try {
            const body = JSON.parse(submitRes.body);
            const submissionId = body.data?.submissionId || body.data?.id;
            if (submissionId) {
                sleep(2);
                const statusRes = http.get(
                    `${CONFIG.BASE_URL}/api/v1/submissions/${submissionId}`,
                    { headers: createHeaders() }
                );
                check(statusRes, {
                    '채점 상태 조회 성공': (r) => r.status === 200,
                });
            }
        } catch (e) {
            // json parse error ignore
        }
    }
}
