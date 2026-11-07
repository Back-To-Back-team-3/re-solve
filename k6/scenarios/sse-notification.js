// =================================================================
// 시나리오 3: 실시간 SSE 알림 스트림 구독 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 7.1.1
// =================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG } from '../config.js';

export function testSseNotificationScenario() {
    const memberId = CONFIG.TEST_MEMBER_ID;

    const sseHeaders = {
        'Accept': 'text/event-stream',
        'X-User-Id': String(memberId),
        'X-User-Role': 'USER',
    };

    // 1. SSE 구독 요청 (api-spec.md > 7.1.1 GET /api/v1/notifications/subscribe)
    // k6는 HTTP 장기 연결 스트림을 테스트할 수 있도록 timeout 옵션 설정
    const res = http.get(
        `${CONFIG.BASE_URL}/api/v1/notifications/subscribe`,
        {
            headers: sseHeaders,
            timeout: '5s',
        }
    );

    check(res, {
        'SSE 연결 수립 성공 (200 OK & text/event-stream)': (r) => {
            const isOk = r.status === 200;
            const isSse = r.headers['Content-Type'] && r.headers['Content-Type'].includes('text/event-stream');
            return isOk && isSse;
        },
    });

    sleep(1);
}
