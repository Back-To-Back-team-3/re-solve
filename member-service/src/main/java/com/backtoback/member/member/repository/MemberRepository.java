package com.backtoback.member.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.member.member.domain.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByGithubId(Long githubId);

    /**
     * {@code profile_version}을 올리는 변경용 조회. 같은 회원의 동시 변경이 같은 버전을 저장하지 않도록 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findForUpdateById(Long id);
}
