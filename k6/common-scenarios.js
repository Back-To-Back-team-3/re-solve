// Re:Solve k6 공통 통합 부하 테스트 메인 스크립트
// 기준 문서:
//   - docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-08
//   - docs/dev/planning/policy-and-state.md > 2. 시간·TTL 정책, 11. 비기능 요구사항
// 실행법:
//   k6 run k6/common-scenarios.js
//   k6 run --env SCENARIO=auth k6/common-scenarios.js
//   k6 run --env BASE_URL=https://staging.re-solve.com k6/common-scenarios.js

import { testAuthScenario } from './scenarios/auth.js';
import { testProblemSubmissionScenario } from './scenarios/problem-submission.js';
import { testSseNotificationScenario } from './scenarios/sse-notification.js';
import { COMMON_THRESHOLDS } from './config.js';

// 전체 시나리오 정의
const ALL_SCENARIOS = {
    // 1. 인증 및 토큰 갱신 부하 (지속적인 로그인/토큰 갱신 요청)
    auth_workload: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { duration: '30s', target: 20 },
            { duration: '1m', target: 20 },
            { duration: '30s', target: 0 },
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
};

// __ENV.SCENARIO 환경변수에 따라 실행할 워크로드를 동적으로 결정한다.
function getActiveScenarios() {
    const selectedScenario = __ENV.SCENARIO;

    if (selectedScenario === 'auth') {
        return { auth_workload: ALL_SCENARIOS.auth_workload };
    }
    if (selectedScenario === 'submission') {
        return { submission_workload: ALL_SCENARIOS.submission_workload };
    }
    if (selectedScenario === 'sse') {
        return { sse_workload: ALL_SCENARIOS.sse_workload };
    }

    // 미지정 시 전체 3개 시나리오 동시 실행
    return ALL_SCENARIOS;
}

export const options = {
    scenarios: getActiveScenarios(),
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
    authScenario();
    submissionScenario();
    sseScenario();
}
