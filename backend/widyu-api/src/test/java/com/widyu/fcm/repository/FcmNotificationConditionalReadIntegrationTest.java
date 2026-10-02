package com.widyu.fcm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FcmNotificationConditionalReadIntegrationTest {
    @Autowired private FcmNotificationRepository notifications;
    @Autowired private MemberRepository members;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private JPAQueryFactory queryFactory;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.deleteAll();
            members.deleteAll();
        });
    }

    @Test
    @DisplayName("첫 읽음이 커밋되면 두 번째 조건부 갱신은 0건이며 최초 읽음 시각을 유지한다")
    void 첫_읽음_커밋후_두번째_갱신은_최초_시각을_유지한다() {
        // given
        Long memberId = new TransactionTemplate(transactionManager).execute(status ->
                members.save(Member.createMember(MemberType.SENIOR, "회원", "01012345678")).getId());
        Long notificationId = new TransactionTemplate(transactionManager).execute(status ->
                notifications.save(FcmNotification.builder()
                        .recipientMember(members.findById(memberId).orElseThrow())
                        .fcmCategory(FcmCategory.ALBUM)
                        .title("알림 제목")
                        .body("알림 본문")
                        .isRead(false)
                        .build()).getId());
        LocalDateTime firstReadAt = LocalDateTime.of(2026, 10, 2, 9, 30);
        LocalDateTime secondReadAt = firstReadAt.plusMinutes(1);

        // when
        int firstUpdated = new TransactionTemplate(transactionManager).execute(status ->
                notifications.markAsReadIfUnread(notificationId, memberId, firstReadAt));
        int secondUpdated = new TransactionTemplate(transactionManager).execute(status ->
                notifications.markAsReadIfUnread(notificationId, memberId, secondReadAt));
        FcmNotification stored = new TransactionTemplate(transactionManager).execute(status ->
                notifications.findById(notificationId).orElseThrow());

        // then
        assertThat(firstUpdated).isEqualTo(1);
        assertThat(secondUpdated).isZero();
        assertThat(stored.isRead()).isTrue();
        assertThat(stored.getReadAt()).isEqualTo(firstReadAt);
    }
}
