package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.member.service.MemberEventPublisher;
import com.backtoback.member.member.service.MemberService;
import com.backtoback.member.skill.client.ProblemDiagnosisClient;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.config.SkillProperties;
import com.backtoback.member.skill.repository.DiagnosisAttemptRepository;
import com.backtoback.member.skill.repository.DiagnosisProblemRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;
import com.backtoback.member.skill.store.DiagnosisDraftStore;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore;

class DiagnosisDisabledTest {

    @Test
    @DisplayName("운영 스위치가 꺼져 있으면 진단 시작은 503이고 DB·problem을 건드리지 않는다")
    void startIsBlockedWhenDisabled() {
        DiagnosisAttemptRepository attempts = mock(DiagnosisAttemptRepository.class);
        MemberTagLevelRepository tagLevels = mock(MemberTagLevelRepository.class);
        ProblemTagClient problemTagClient = mock(ProblemTagClient.class);
        ProblemDiagnosisClient problemDiagnosisClient = mock(ProblemDiagnosisClient.class);
        DiagnosisService service
            = new DiagnosisService(
                attempts,
                mock(DiagnosisProblemRepository.class),
                tagLevels,
                mock(DiagnosisDraftStore.class),
                mock(DiagnosisSubmissionStore.class),
                mock(MemberEventPublisher.class),
                mock(MemberService.class),
                problemTagClient,
                problemDiagnosisClient,
                new SkillProperties("http://problem-service", false, Duration.ofDays(3), Map.of()),
                mock(TransactionTemplate.class),
                Clock.systemUTC()
            );

        assertThatThrownBy(() -> service.start(7L, 3L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        verifyNoInteractions(attempts, tagLevels, problemTagClient, problemDiagnosisClient);
    }
}
