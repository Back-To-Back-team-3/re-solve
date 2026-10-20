package com.backtoback.judge.judge0.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.judge.global.exception.custom.Judge0ConnectionFailedException;
import com.backtoback.judge.global.exception.custom.Judge0ResponseFailedException;
import com.backtoback.judge.judge0.dto.response.Judge0LanguageResponse;

@Service
public class Judge0LanguageClient {
    private static final Logger LOG = LoggerFactory.getLogger(Judge0LanguageClient.class);
    private final String baseUrl;
    private final RestClient client;

    /**
     * Judge0 언어 목록 조회에 필요한 접속 설정과 HTTP 클라이언트를 연결한다.
     *
     * @param baseUrl Judge0 서버 주소
     * @param client 연결·응답 제한과 인증이 적용된 Judge0 클라이언트
     */
    public Judge0LanguageClient(
        @Value("${judge0.base-url}") String baseUrl,
        @Qualifier("judge0RestClient") RestClient client
    ) {
        this.baseUrl = baseUrl;
        this.client = client;
    }

    /**
     * Judge0의 전체 활성 실행 환경 목록을 한 번 조회하고 응답 형식을 검증한다.
     * 언어 선택과 캐시 관리는 호출하는 서비스에서 담당하며 자동 재시도하지 않는다.
     *
     * @return 검증한 수정 불가능 목록; 서버 주소 미설정이면 빈 목록
     * @throws Judge0ConnectionFailedException 연결 실패 또는 통신 시간 초과가 발생한 경우
     * @throws Judge0ResponseFailedException HTTP 오류 또는 올바르지 않은 응답을 받은 경우
     */
    public List<Judge0LanguageResponse> findAll() {
        // 서버 주소가 없는 개발 환경에서는 외부 호출 없이 빈 목록을 반환한다.
        if (baseUrl == null || baseUrl.isBlank()) {
            return List.of();
        }
        try {
            // Judge0의 JSON 배열을 ID·이름으로 구성된 응답 DTO 배열로 변환한다.
            var response = client.get().uri("/languages/").retrieve().toEntity(Judge0LanguageResponse[].class);
            Judge0LanguageResponse[] body = response.getBody();
            // 본문 누락은 정상적인 빈 배열([])과 구분하여 응답 오류로 처리한다.
            if (response.getStatusCode().value() != 200 || body == null) {
                throw responseFailure(response.getStatusCode().value());
            }
            // 불완전한 항목이나 중복 ID가 있으면 목록을 반환하지 않는다.
            var ids = new HashSet<Integer>();
            for (Judge0LanguageResponse runtime : body) {
                // Set.add는 이미 있는 ID이면 false를 반환하므로 !ids.add로 중복도 검사한다.
                if (runtime == null || runtime.id() == null || runtime.id() <= 0 || runtime.name() == null
                    || runtime.name().isBlank() || !ids.add(runtime.id())) {
                    throw responseFailure(response.getStatusCode().value());
                }
            }
            // 지원 언어나 최신 버전을 고르지 않고, 고정 환경 검증에 필요한 과거 버전도 그대로 반환한다.
            return List.copyOf(Arrays.asList(body));
        } catch (ResourceAccessException exception) {
            // 연결 실패·시간 초과를 응답 오류와 구분한다.
            LOG.warn("Judge0 연결 실패: exception={}", exception.getClass().getSimpleName());
            throw new Judge0ConnectionFailedException();
        } catch (RestClientResponseException exception) {
            // HTTP 오류는 응답 본문을 노출하지 않고 상태 코드만 오류 처리기에 전달한다.
            throw responseFailure(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            // JSON 변환 실패 등 나머지 클라이언트 오류는 상태 코드를 특정하지 않고 처리한다.
            throw responseFailure(null);
        }
    }

    /**
     * 원본 코드나 외부 응답 본문 없이 응답 오류를 기록하고 예외를 생성한다.
     *
     * @param status 외부 HTTP 상태; 확인할 수 없으면 null
     * @return 상위 실행기에 전달할 응답 오류 예외
     */
    private Judge0ResponseFailedException responseFailure(Integer status) {
        LOG.warn("Judge0 언어 목록 조회 실패: status={}", status);
        return new Judge0ResponseFailedException(status);
    }
}
