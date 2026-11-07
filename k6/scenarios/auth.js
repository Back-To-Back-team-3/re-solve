// =================================================================
// 시나리오 1: 인증 및 토큰 재발급 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 1. 인증/회원
// =================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, createHeaders } from '../config.js';

export function testAuthScenario() {
    const memberId = Math.floor(Math.random() * 100) + 1;

    // 1. 개발자 로그인 요청 (api-spec.md > 1.1.2)
    const loginPayload = JSON.stringify({
        memberId: memberId,
    });

    const loginRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/auth/dev-login`,
        loginPayload,
        { headers: createHeaders() }
    );

    const loginSuccess = check(loginRes, {
        '로그인 성공 (200 OK 또는 토큰 반환)': (r) => r.status === 200,
    });

    let accessToken = null;
    if (loginSuccess) {
        try {
            const body = JSON.parse(loginRes.body);
            if (body.data && body.data.accessToken) {
                accessToken = body.data.accessToken;
            }
        } catch (e) {
            // json parse ignore
        }
    }

    sleep(1);

    // 2. 토큰 재발급 요청 (api-spec.md > 1.1.4)
    const refreshRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/auth/token/refresh`,
        null,
        { headers: createHeaders(accessToken) }
    );

    check(refreshRes, {
        '토큰 갱신 응답 수신 (200 또는 401)': (r) => r.status === 200 || r.status === 401,
    });
}
