// =================================================================
// Re:Solve k6 공통 통합 부하 테스트 메인 스크립트
// 기준 문서:
//   - docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08
//   - docs/dev/planning/policy-and-state.md > 2. 시간·TTL 정책
// 실행법:
//   k6 run k6/common-scenarios.js
//   k6 run --env BASE_URL=http://staging.re-solve.com k6/common-scenarios.js
// =================================================================

import { testAuthScenario } from './scenarios/auth.js';
import { testProblemSubmissionScenario } from './scenarios/problem-submission.js';
import { testSseNotificationScenario } from './scenarios/sse-notification.js';
import { COMMON_THRESHOLDS } from './config.js';

export const options = {
    scenarios: {
        // 1. 인증 및 토큰 갱신 부하 (지속적인 로그인 요청)
        auth_workload: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 20 },  // 점진적 상승
                { duration: '1m', target: 20 },   // 유지
                { duration: '30s', target: 0 },   // 하강
            ],
            exec: 'authScenario',
        },
        // 2. 문제 조회 및 코드 제출 부하 (핵심 비즈니스 흐름)
        submission_workload: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 15 },
                { duration: '1m', target: 15 },
                { duration: '30s', target: 0 },
            ],
            exec: 'submissionScenario',
        },
        // 3. 실시간 알림 SSE 연결 부하 (장기 커넥션 유지 점검)
        sse_workload: {
            executor: 'constant-vus',
            vus: 10,
            duration: '2m',
            exec: 'sseScenario',
        },
    },
    thresholds: COMMON_THRESHOLDS,
};

export function authScenario() {
    testAuthScenario();
}

export function submissionScenario() {
    testProblemSubmissionScenario();
}

export function sseScenario() {
    testSseNotificationScenario();
}

export default function () {
    // 기본 실행 엔트리포인트 (개별 시나리오 순차 테스트)
    authScenario();
    submissionScenario();
    sseScenario();
}
