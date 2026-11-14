package com.backtoback.study.replica.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberAcReplicaTest {

    @Test
    @DisplayName("verdict가 AC이면 accepted는 true로 계산된다")
    void syncWithAcceptedVerdict() {
        LocalDateTime now = LocalDateTime.now();

        MemberAcReplica replica = MemberAcReplica.sync(1L, 2L, 3L, now, 1, JudgeVerdict.AC, now);

        assertThat(replica.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("verdict가 AC가 아니면 accepted는 false로 계산된다")
    void syncWithRejectedVerdict() {
        LocalDateTime now = LocalDateTime.now();

        MemberAcReplica replica = MemberAcReplica.sync(1L, 2L, 3L, now, 1, JudgeVerdict.WA, now);

        assertThat(replica.isAccepted()).isFalse();
    }
}
