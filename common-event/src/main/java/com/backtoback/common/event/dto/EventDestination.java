package com.backtoback.common.event.dto;

/**
 * 서비스가 명시적으로 선택한 Standard SNS 토픽 또는 SQS 큐다.
 * 실제 리소스 주소·권한·서비스별 이벤트 분류는 사용하는 서비스가 구성한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.3 발행 규칙·§2 채널 토폴로지: SNS/SQS Standard 전달</li>
 * </ul>
 *
 * @param channel SNS 발행 또는 SQS 직접 전달 구분
 * @param address 토픽 ARN 또는 큐 URL
 */
public record EventDestination(Channel channel, String address) {
    /**
     * 빈 주소와 FIFO 리소스를 거절해 문서에서 정한 Standard 전달만 허용한다.
     *
     * @throws IllegalArgumentException 채널·주소가 없거나 FIFO 주소일 때
     */
    public EventDestination {
        if (channel == null || address == null || address.isBlank() || address.endsWith(".fifo")) {
            throw new IllegalArgumentException("A Standard SNS topic or SQS queue address is required");
        }
    }

    /**
     * 메시지 전달 방식 구분이다. 도메인 이벤트의 종류와 분리한다.
     */
    public enum Channel {
        // 구독 필터에 따라 여러 서비스로 전달할 도메인 이벤트다.
        SNS,
        // 지정한 큐의 소비자에게 직접 전달할 작업 요청이다.
        SQS
    }
}
