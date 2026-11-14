package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.problem.problem.dto.request.TestCaseRequest;

import tools.jackson.databind.json.JsonMapper;

/** 직렬화 원문과 바이트 메타 정보가 서로 일치하는지 확인한다. */
class ProblemAssetSerializerTest {
    private final ProblemAssetSerializer serializer = new ProblemAssetSerializer();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 본문의 각 영역을 역직렬화해 원래 문자열과 비교한다. */
    @Test
    @DisplayName("본문 세 문자열의 한글과 공백·줄바꿈·이스케이프를 UTF-8 JSON에서 복원한다")
    void preservesBodyStrings() {
        // given
        String description = "설명 \"따옴표\"\n다음 줄  ";
        String constraints = "\t제한\\경로";
        String examples = "예시\r\n마지막";

        // when
        ProblemAssetContent content = serializer.serializeBody(description, constraints, examples);
        var body = mapper.readTree(content.bytes());

        // then
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("description").stringValue()).isEqualTo(description);
        assertThat(body.get("constraints").stringValue()).isEqualTo(constraints);
        assertThat(body.get("examples").stringValue()).isEqualTo(examples);
        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).contains("설명");
        assertThat(content.sizeBytes()).isEqualTo(content.bytes().length);
    }

    /** 테스트 파일에서 입력과 기대값 문자열을 확인한다. */
    @Test
    @DisplayName("테스트 파일은 Judge의 입력·기대값 필드로 원문 문자열을 복원한다")
    void preservesJudgeTestContent() {
        // given
        Map<String, String> arguments = Map.of("number", "9007199254740993", "text", "\"한글\\n문자\"");
        String expected = " [ 9007199254740993, \"결과\" ] ";

        // when
        ProblemAssetContent content = serializer.serializeTest(new TestCaseRequest(true, arguments, expected));
        var test = mapper.readTree(content.bytes());

        // then
        assertThat(test.size()).isEqualTo(2);
        assertThat(test.get("argumentTexts").get("number").stringValue()).isEqualTo(arguments.get("number"));
        assertThat(test.get("argumentTexts").get("text").stringValue()).isEqualTo(arguments.get("text"));
        assertThat(test.get("expectedReturnText").stringValue()).isEqualTo(expected);
        assertThat(test.get("isSample")).isNull();
    }

    /** 입력 맵 순서와 공개 여부가 저장 바이트에 미치는 영향을 확인한다. */
    @Test
    @DisplayName("입력 키 순서나 공개 여부가 달라도 같은 문자열은 같은 테스트 파일이 된다")
    void serializesEqualInputsConsistently() {
        // given
        Map<String, String> first = new LinkedHashMap<>();
        first.put("z", "2");
        first.put("a", "1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("a", "1");
        second.put("z", "2");

        // when
        ProblemAssetContent left = serializer.serializeTest(new TestCaseRequest(true, first, "3"));
        ProblemAssetContent right = serializer.serializeTest(new TestCaseRequest(false, second, "3"));

        // then
        assertThat(left.bytes()).isEqualTo(right.bytes());
        assertThat(left.sha256()).isEqualTo(right.sha256());
        assertThat(left.sizeBytes()).isEqualTo(right.sizeBytes());
    }

    /** JSON 텍스트 내부의 공백 차이를 유지하는지 확인한다. */
    @Test
    @DisplayName("테스트 원문 문자열의 공백이 달라지면 파일 바이트도 달라진다")
    void preservesWhitespaceInsideTestStrings() {
        // when
        ProblemAssetContent first = serializer.serializeTest(new TestCaseRequest(true, Map.of("a", "[1,2]"), "3"));
        ProblemAssetContent second = serializer.serializeTest(new TestCaseRequest(true, Map.of("a", "[1, 2]"), "3"));

        // then
        assertThat(first.bytes()).isNotEqualTo(second.bytes());
        assertThat(first.sha256()).isNotEqualTo(second.sha256());
    }

    /** 고정 JSON 바이트와 알려진 체크섬을 대조한다. */
    @Test
    @DisplayName("테스트 JSON의 체크섬과 크기는 직렬화된 파일 바이트와 일치한다")
    void hashesSerializedTestBytes() {
        // when
        ProblemAssetContent content = serializer.serializeTest(new TestCaseRequest(true, Map.of("a", "1"), "2"));
        String json = "{\"argumentTexts\":{\"a\":\"1\"},\"expectedReturnText\":\"2\"}";

        // then
        assertThat(content.bytes()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
        assertThat(content.sizeBytes()).isEqualTo(json.getBytes(StandardCharsets.UTF_8).length);
        assertThat(content.sha256()).isEqualTo("e35f261c7853ec7d8875e2e9213f2ec465103d233f7f8e7d6d8de38afde310e3");
    }

    /** 생성 전후 배열 변경이 저장 내용에 미치는 영향을 확인한다. */
    @Test
    @DisplayName("체크섬과 크기는 저장할 바이트 기준이며 외부 배열 변경에 영향을 받지 않는다")
    void hashesAndProtectsExactBytes() {
        // given
        byte[] original = "abc".getBytes(StandardCharsets.UTF_8);
        ProblemAssetContent content = ProblemAssetContent.fromBytes(original);
        original[0] = 'z';
        // when
        byte[] copy = content.bytes();
        copy[1] = 'z';

        // then
        assertThat(content.bytes()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(content.sizeBytes()).isEqualTo(3);
        assertThat(content.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
