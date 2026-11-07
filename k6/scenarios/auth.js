// 시나리오 1: 인증 및 토큰 재발급 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 1.1.6 개발용 로그인, 1.1.4 토큰 재발급

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, createHeaders } from '../config.js';

export function testAuthScenario() {
    const memberIdNum = (typeof __VU !== 'undefined' && __VU > 0) ? __VU : Math.floor(Math.random() * 100) + 1;
    const memberId = String(memberIdNum);

    // 1. 개발용 로그인 요청 (api-spec.md > 1.1.6)
    // ID는 명세에 따라 문자열로 전송한다.
    const loginPayload = JSON.stringify({
        memberId: memberId,
    });

    const loginRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/auth/dev-login`,
        loginPayload,
        { headers: createHeaders(null, null, memberId) }
    );

    const loginSuccess = check(loginRes, {
        '개발용 로그인 성공 (200 OK)': (r) => r.status === 200,
    });

    let accessToken = null;
    let refreshToken = null;

    if (loginSuccess) {
        try {
            const body = JSON.parse(loginRes.body);
            if (body.data && body.data.accessToken) {
                accessToken = body.data.accessToken;
            }
        } catch (e) {
            // JSON 파싱 실패 무시
        }

        // Set-Cookie 헤더에서 refreshToken 값을 추출한다.
        if (loginRes.cookies && loginRes.cookies.refreshToken && loginRes.cookies.refreshToken.length > 0) {
            refreshToken = loginRes.cookies.refreshToken[0].value;
        }
    }

    sleep(1);

    // 2. 토큰 재발급 요청 (api-spec.md > 1.1.4)
    // Refresh Token은 Cookie 헤더로 전달한다.
    const refreshHeaders = createHeaders(accessToken, null, memberId);
    if (refreshToken) {
        refreshHeaders['Cookie'] = `refreshToken=${refreshToken}`;
    }

    const refreshRes = http.post(
        `${CONFIG.BASE_URL}/api/v1/auth/token/refresh`,
        null,
        { headers: refreshHeaders }
    );

    check(refreshRes, {
        '토큰 재발급 성공 (200 OK)': (r) => r.status === 200,
    });
}
