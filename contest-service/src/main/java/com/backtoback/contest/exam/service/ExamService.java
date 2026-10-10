package com.backtoback.contest.exam.service;

import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.request.CreateExamRequest;
import com.backtoback.contest.exam.dto.response.ExamResponses;
import com.backtoback.contest.global.error.BusinessException;

/**
 * 여섯 외부 API를 연결하는 애플리케이션 서비스 계약이다.
 * HTTP 입력 검증은 Controller, 실제 DB·권한·가드·잠금·이벤트 처리는 후속 구현이 담당한다.
 * 개발 stub을 실제 저장·자격·시간 판정 완료로 해석해서는 안 된다.
 * <p>기준 문서: API 명세서 v1.1 / §4.1.1~§4.1.3, §4.2.1~§4.2.3: API 서비스 경계.
 */
public interface ExamService {
    /**
     * 생성 권한과 공개 회차를 확인해 시험을 생성하는 계약이다.
     *
     * @param studyId 대상 스터디
     * @param memberId Gateway가 전달한 요청자
     * @param request 검증한 생성 입력
     * @return 생성 시험과 고정 회차
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    ExamResponses.Created create(String studyId, String memberId, CreateExamRequest request);

    /**
     * 승인 구성원 또는 기존 등록자에게 허용되는 시험 목록 조회 계약이다.
     *
     * @param studyId 대상 스터디
     * @param memberId 요청자
     * @param status 상태 필터, 없으면 null
     * @param page 0부터의 페이지
     * @param size 최대 100의 페이지 크기
     * @param sort startsAt·createdAt 및 asc·desc 정렬
     * @return 문제 정보를 노출하지 않는 목록
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    ExamResponses.Page list(String studyId, String memberId, ExamStatus status, int page, int size, String sort);

    /**
     * 상세를 조회하되 입장 시각·상태를 변경하지 않는 계약이다.
     *
     * @param examId 시험 ID
     * @param memberId 요청자
     * @return 시험 설정과 본인 참가 정보
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    ExamResponses.Detail getDetail(String examId, String memberId);

    /**
     * 활성 참가가 없으면 새 등록, 있으면 기존 참가를 반환하는 계약이다.
     *
     * @param examId 시험 ID
     * @param memberId 인증 정보의 요청자
     * @return 신규 여부와 참가 데이터, HTTP 201·200 판단에 사용
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    Registration register(String examId, String memberId);

    /**
     * 최초 입장을 기록하고 재입장은 최초 시각을 유지하는 계약이다.
     *
     * @param examId 시험 ID
     * @param memberId 활성 등록 본인
     * @return 최초 입장 시각과 개인 마감
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    ExamResponses.Started start(String examId, String memberId);

    /**
     * 실제 입장한 본인의 고정 회차 문제를 조회하는 계약이다.
     * 조회만으로 입장시키지 않으며 숨김 테스트·정답 코드를 제외한다.
     *
     * @param examId 시험 ID
     * @param memberId 입장한 본인
     * @return 화면 본문과 공개 실행 예제
     * @throws BusinessException 개발 대역에서 명세 오류 시나리오가 선택된 경우
     */
    ExamResponses.Problems getProblems(String examId, String memberId);

    /**
     * HTTP 상태를 DTO 데이터와 분리해 내부 신규 여부를 외부 JSON에 노출하지 않는다.
     *
     * @param created 신규 참가면 true, 기존 활성 참가면 false
     * @param data 외부 참가 응답 데이터
     */
    record Registration(boolean created, ExamResponses.Registered data) {
    }
}
