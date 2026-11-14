package com.backtoback.problem.tag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.backtoback.problem.global.lock.TransactionLock;
import com.backtoback.problem.tag.domain.Tag;
import com.backtoback.problem.tag.repository.TagRepository;

/** 순번 서비스의 트랜잭션 경계가 잠금·DB 접근 전에 적용되는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class TagOrderServiceTest {
    @Mock
    private TagRepository tagRepository;
    @Mock
    private TransactionLock transactionLock;

    @ParameterizedTest
    @MethodSource("orderOperations")
    @DisplayName("활성 트랜잭션 없이 순번 서비스를 호출하면 잠금과 DB 접근 전에 거부한다")
    void rejectsCallsWithoutTransaction(Consumer<TagOrderService> operation) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(mock(DataSource.class)));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory(new TagOrderService(tagRepository, transactionLock));
        proxyFactory.addAdvice(interceptor);
        TagOrderService service = (TagOrderService)proxyFactory.getProxy();

        assertThatThrownBy(() -> operation.accept(service)).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(tagRepository, transactionLock);
    }

    static Stream<Consumer<TagOrderService>> orderOperations() {
        return Stream
            .<Consumer<TagOrderService>>of(
                TagOrderService::lock,
                service -> service.makeRoomFor(0),
                service -> service.move(Tag.create("태그", 0), 1),
                TagOrderService::closeGap
            );
    }
}
