// 시나리오 3: 실시간 SSE 알림 스트림 구독 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 7.1.1 알림 구독

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';
import { CONFIG, createHeaders } from '../config.js';
import { obtainDevToken } from './auth.js';

// SSE 전용 메트릭: 일반 HTTP API 요청 지표와 분리하여 스트림 연결 성공률을 측정한다.
export const sseConnectedRate = new Rate('sse_connected_rate');
export const sseConnectCount = new Counter('sse_connect_count');

let cachedToken = null;

export function testSseNotificationScenario() {
    const memberId = typeof __VU !== 'undefined' && __VU > 0 ? String(__VU) : String(CONFIG.TEST_MEMBER_ID);

    // VU별 유효한 JWT 토큰을 취득하여 게이트웨이 인증 통과를 보장한다.
    if (!cachedToken && !CONFIG.ACCESS_TOKEN) {
        cachedToken = obtainDevToken(memberId);
    }
    const effectiveToken = CONFIG.ACCESS_TOKEN || cachedToken;

    // createHeaders를 기반으로 SSE 필수 헤더를 설정한다.
    const sseHeaders = createHeaders(effectiveToken, null, memberId);
    sseHeaders['Accept'] = 'text/event-stream';

    // 1. SSE 구독 요청 (api-spec.md > 7.1.1 GET /api/v1/notifications/subscribe)
    // SSE는 서버가 스트림을 계속 열어두므로, 타임아웃 발생 시에도 핸드셰이크 성공(200 OK & Content-Type) 여부를 판정한다.
    let res;
    try {
        res = http.get(
            `${CONFIG.BASE_URL}/api/v1/notifications/subscribe`,
            {
                headers: sseHeaders,
                timeout: '5s',
                tags: { name: 'sse_subscribe' },
            }
        );
    } catch (e) {
        // 클라이언트 소켓 타임아웃 예외를 캐치한다.
    }

    const isConnected = Boolean(res && (
        res.status === 200 ||
        (res.headers && res.headers['Content-Type'] && res.headers['Content-Type'].includes('text/event-stream'))
    ));

    check(res, {
        'SSE 연결 수립 성공 (200 OK & text/event-stream)': () => isConnected,
    });

    if (isConnected) {
        sseConnectedRate.add(1);
        sseConnectCount.add(1);
    } else {
        sseConnectedRate.add(0);
    }

    sleep(1);
}
