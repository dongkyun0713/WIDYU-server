package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FcmAdminDeliveryContractTest {
    @Test
    @DisplayName("관리자 예산이 단일 HTTP 제한과 같으면 잘못된 설정으로 거부한다")
    void 관리자_예산이_HTTP와_같으면_거부한다() {
        // given
        ReflectionTestUtils.setField(fcmService, "adminTimeout", Duration.ofSeconds(10));
        // when / then
        assertThatThrownBy(fcmService::validateTimeouts)
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Mock private FcmOutboxService outboxService;
    @Mock private FcmTransport transport;
    @Mock private MemberFcmTokenRepository memberFcmTokenRepository;
    @InjectMocks private FcmService fcmService;

    @Test
    @DisplayName("관리자 테스트를 요청하면 HTTP 성공 토큰 수만 반환한다")
    void 관리자_테스트는_HTTP_성공_토큰_수만_반환한다() {
        // given
        FcmSendDto dto = new FcmSendDto("제목", "내용", FcmCategory.ALBUM, "", "");
        List<MemberFcmToken> tokens = List.of(token("ok"), token("retry"), token("invalid"));
        given(memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(1L)).willReturn(tokens);
        given(transport.send(eq("ok"), eq(dto), any(BooleanSupplier.class))).willReturn(FcmTransport.Result.delivered());
        given(transport.send(eq("retry"), eq(dto), any(BooleanSupplier.class))).willReturn(FcmTransport.Result.retry(Duration.ofSeconds(1)));
        given(transport.send(eq("invalid"), eq(dto), any(BooleanSupplier.class))).willReturn(FcmTransport.Result.rejected(true));

        // when
        int sent = fcmService.sendTestMessageToUser(1L, dto);

        // then
        assertThat(sent).isEqualTo(1);
        then(transport).should().send(eq("ok"), eq(dto), any(BooleanSupplier.class));
        then(transport).should().send(eq("retry"), eq(dto), any(BooleanSupplier.class));
        then(transport).should().send(eq("invalid"), eq(dto), any(BooleanSupplier.class));
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("활성 토큰이 없으면 관리자 테스트 성공 수로 영을 반환한다")
    void 활성_토큰이_없으면_영을_반환한다() {
        // given
        FcmSendDto dto = new FcmSendDto("제목", "내용", FcmCategory.ALBUM, "", "");
        given(memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(1L)).willReturn(List.of());

        // when
        int sent = fcmService.sendTestMessageToUser(1L, dto);

        // then
        assertThat(sent).isZero();
        verifyNoInteractions(transport, outboxService);
    }

    @Test
    @DisplayName("관리자 전체 시간 예산이 소진되면 나머지 토큰을 보내지 않고 기존 성공 수를 반환한다")
    void 전체_시간_예산이_소진되면_나머지_토큰을_보내지_않는다() {
        // given
        FcmSendDto dto = new FcmSendDto("제목", "내용", FcmCategory.ALBUM, "", "");
        ReflectionTestUtils.setField(fcmService, "httpTimeout", Duration.ofMillis(1));
        ReflectionTestUtils.setField(fcmService, "adminTimeout", Duration.ofMillis(100));
        given(memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(1L))
                .willReturn(List.of(token("first"), token("second")));
        given(transport.send(eq("first"), eq(dto), any(BooleanSupplier.class)))
                .willAnswer(invocation -> {
                    Thread.sleep(150);
                    return FcmTransport.Result.delivered();
                });

        // when
        int sent = fcmService.sendTestMessageToUser(1L, dto);

        // then
        assertThat(sent).isEqualTo(1);
        then(transport).should(never()).send(eq("second"), eq(dto), any(BooleanSupplier.class));
        verifyNoInteractions(outboxService);
    }

    private MemberFcmToken token(String value) {
        return MemberFcmToken.builder().token(value).active(true).build();
    }
}
