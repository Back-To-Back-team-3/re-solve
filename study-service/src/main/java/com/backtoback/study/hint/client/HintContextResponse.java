package com.backtoback.study.hint.client;

import java.util.List;

/** problem-service 힌트 문맥 응답 (api-spec §2.6.6). */
public record HintContextResponse(
    boolean isHintAvailable,
    Long problemRevisionId,
    String problemTitle,
    String problemBody,
    List<String> problemTags
) {
}
