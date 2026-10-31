package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.client.SubmissionJudgingDataClient;
import com.backtoback.judge.judging.client.TestAssetClient;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.submission.domain.Submission;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SubmissionTestResultServiceTest {
    @Mock
    private SubmissionHistoryReader history;
    @Mock
    private SubmissionJudgingDataClient dataClient;
    @Mock
    private TestAssetClient assets;

    @ParameterizedTest
    @CsvSource(
        {
            "AC,true",
            "WA,false",
            "SYSTEM_ERROR,null"
        }
    )
    @DisplayName("숨김 테스트는 번호와 통과 여부 두 필드만 직렬화한다")
    void hidesDetails(JudgeVerdict verdict, String expected) {
        var test = mock(JudgeTestResult.class);
        given(test.getTestOrder()).willReturn(1);
        given(test.getCreatedTryCount()).willReturn(1);
        given(test.getTokenStatus()).willReturn(JudgeTestResult.TokenStatus.DONE);
        given(test.getVerdict()).willReturn(verdict);
        var job = job();
        given(job.getTryCount()).willReturn(1);
        given(history.read(7, 1))
            .willReturn(
                new SubmissionHistory(
                    mock(Submission.class),
                    List.of(new SubmissionHistory.Attempt(job, List.of(test)))
                )
            );
        var response = new SubmissionTestResultService(history, dataClient, assets).read(7, 1, 1);
        var node = JsonMapper.builder().build().valueToTree(response).get("content").get(0);
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.get("testOrder").asInt()).isEqualTo(1);
        assertThat(node.get("passed").toString()).isEqualTo(expected);
        verifyNoInteractions(dataClient, assets);
    }

    @Test
    @DisplayName("이전 실행 시도의 숨김 판정을 현재 통과 여부로 노출하지 않는다")
    void masksOldTry() {
        var test = mock(JudgeTestResult.class);
        given(test.getTestOrder()).willReturn(1);
        given(test.getCreatedTryCount()).willReturn(1);
        var job = job();
        given(job.getTryCount()).willReturn(2);
        given(history.read(7, 1))
            .willReturn(
                new SubmissionHistory(
                    mock(Submission.class),
                    List.of(new SubmissionHistory.Attempt(job, List.of(test)))
                )
            );
        var response = new SubmissionTestResultService(history, dataClient, assets).read(7, 1, 1);
        assertThat(JsonMapper.builder().build().valueToTree(response).get("content").get(0).get("passed").isNull())
            .isTrue();
    }

    @Test
    @DisplayName("공개 테스트만 고정 회차의 원문과 실제 출력을 연결한다")
    void loadsPublicSource() {
        var submission = mock(Submission.class);
        given(submission.getProblemId()).willReturn(10L);
        given(submission.getProblemRevisionId()).willReturn(20L);
        var test = mock(JudgeTestResult.class);
        given(test.isSample()).willReturn(true);
        given(test.getTestAssetId()).willReturn(30L);
        given(test.getTestOrder()).willReturn(1);
        given(test.getCreatedTryCount()).willReturn(1);
        given(test.getTokenStatus()).willReturn(JudgeTestResult.TokenStatus.DONE);
        given(test.getVerdict()).willReturn(JudgeVerdict.AC);
        given(test.getActualOutput()).willReturn("3");
        var job = job();
        given(job.getTryCount()).willReturn(1);
        given(history.read(7, 1))
            .willReturn(new SubmissionHistory(submission, List.of(new SubmissionHistory.Attempt(job, List.of(test)))));
        var asset = new SubmissionJudgingDataResponse.Asset("key", "version", "a".repeat(64));
        given(dataClient.find(10, 20))
            .willReturn(
                new SubmissionJudgingDataResponse(
                    "10",
                    "20",
                    "title",
                    1,
                    List.of("tag"),
                    List.of("1"),
                    null,
                    null,
                    List.of(new SubmissionJudgingDataResponse.TestCase("30", 1, true, asset))
                )
            );
        given(assets.read(asset)).willReturn(new SubmissionJudgingDataResponse.TestContent(Map.of("a", "1"), "3"));
        var response = new SubmissionTestResultService(history, dataClient, assets).read(7, 1, 1);
        var node = JsonMapper.builder().build().valueToTree(response).get("content").get(0);
        assertThat(node.get("argumentTexts").get("a").asString()).isEqualTo("1");
        assertThat(node.get("actualReturnText").asString()).isEqualTo("3");
        verify(assets).read(asset);
    }

    @Test
    @DisplayName("권한이 없거나 회차가 없으면 원문 조회 전에 거절한다")
    void rejectsBeforeRemoteCall() {
        var service = new SubmissionTestResultService(history, dataClient, assets);
        given(history.read(7, 1)).willThrow(new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
        assertThatThrownBy(() -> service.read(7, 1, 1)).isInstanceOf(JudgeException.class);
        given(history.read(7, 2)).willReturn(new SubmissionHistory(mock(Submission.class), List.of()));
        assertThatThrownBy(() -> service.read(7, 2, 99)).isInstanceOf(JudgeException.class);
        verifyNoInteractions(dataClient, assets);
    }

    private JudgeJob job() {
        var job = mock(JudgeJob.class);
        given(job.getJudgeAttempt()).willReturn(1);
        given(job.getStatus()).willReturn(JudgeJob.Status.RUNNING);
        return job;
    }
}
