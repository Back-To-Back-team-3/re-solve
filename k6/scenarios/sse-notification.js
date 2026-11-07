// 시나리오 3: 실시간 SSE 알림 스트림 구독 부하 테스트
// 기준 문서: docs/dev/specifications/api-spec.md > 7.1.1 알림 구독

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, createHeaders } from '../config.js';

export function testSseNotificationScenario() {
    const memberId = typeof __VU !== 'undefined' && __VU > 0 ? String(__VU) : String(CONFIG.TEST_MEMBER_ID);

    // createHeaders를 기반으로 SSE 필수 헤더를 설정한다.
    const sseHeaders = createHeaders(null, null, memberId);
    sseHeaders['Accept'] = 'text/event-stream';

    // 1. SSE 구독 요청 (api-spec.md > 7.1.1 GET /api/v1/notifications/subscribe)
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
