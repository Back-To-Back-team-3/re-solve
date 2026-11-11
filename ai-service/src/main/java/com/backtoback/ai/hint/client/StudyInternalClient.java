package com.backtoback.ai.hint.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.backtoback.ai.hint.config.StudyClientProperties;

@Component
public class StudyInternalClient {

    private final RestClient restClient;

    public StudyInternalClient(StudyClientProperties studyClientProperties) {
        this.restClient = RestClient.builder().baseUrl(studyClientProperties.baseUrl()).build();
    }

    /** study-service 내부 API로 hint_requests 상태를 GENERATING으로 전이한다. */
    public void transitionToGenerating(String hintRequestId) {
        restClient
            .patch()
            .uri("/internal/v1/hint-requests/{id}/generating", hintRequestId)
            .retrieve()
            .onStatus(status -> status.value() == 409, (req, res) -> {
                throw new HintGeneratingConflictException(hintRequestId);
            })
            .toBodilessEntity();
    }
}
