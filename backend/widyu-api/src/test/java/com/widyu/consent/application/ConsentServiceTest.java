package com.widyu.consent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.ConsentRecord;
import com.widyu.consent.ConsentSource;
import com.widyu.consent.dto.request.ConsentSubmitRequest;
import com.widyu.consent.dto.request.ConsentWithdrawRequest;
import com.widyu.consent.dto.response.ConsentItemResponse;
import com.widyu.consent.dto.response.ConsentRecordResponse;
import com.widyu.consent.dto.response.ConsentStateResponse;
import com.widyu.consent.repository.ConsentRecordRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.repository.MemberRepository;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentService 인앱 동의 기록 단위 테스트")
class ConsentServiceTest {

    private static final Long MEMBER_ID = 1023L;
    private static final String VERSION = "app-consent-v1";

    @Mock private ConsentRecordRepository consentRecordRepository;
    @Mock private MemberRepository memberRepository;

    @Captor private ArgumentCaptor<List<ConsentRecord>> recordsCaptor;

    @InjectMocks private ConsentService consentService;

    private static ConsentRecord record(
            ConsentKey key, boolean granted, String version, LocalDateTime recordedAt) {
        return ConsentRecord.of(MEMBER_ID, key, version, granted, recordedAt, ConsentSource.APP);
    }

    @Test
    @DisplayName("항목 세 개를 제출하면 판·시각·출처가 담긴 행 세 개가 저장되고 현재 상태에 세 항목이 담긴다")
    void 항목_세_개를_제출하면_행_세_개가_저장되고_현재_상태에_담긴다() {
        // given
        Map<String, Boolean> consents = new LinkedHashMap<>();
        consents.put("PRIVACY_PERSONAL", true);
        consents.put("LOCATION", true);
        consents.put("LOCATION_NOTICE_BATCHED", false);
        ConsentSubmitRequest request = ConsentSubmitRequest.of(VERSION, consents);

        LocalDateTime recordedAt = LocalDateTime.of(2026, 9, 21, 10, 0);
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of(
                        record(ConsentKey.PRIVACY_PERSONAL, true, VERSION, recordedAt),
                        record(ConsentKey.LOCATION, true, VERSION, recordedAt),
                        record(ConsentKey.LOCATION_NOTICE_BATCHED, false, VERSION, recordedAt)));

        // when
        ConsentStateResponse response = consentService.submit(MEMBER_ID, request);

        // then
        verify(consentRecordRepository).saveAll(recordsCaptor.capture());
        List<ConsentRecord> saved = recordsCaptor.getValue();
        assertThat(saved).hasSize(3);
        assertThat(saved).allSatisfy(saved0 -> {
            assertThat(saved0.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(saved0.getVersion()).isEqualTo(VERSION);
            assertThat(saved0.getSource()).isEqualTo(ConsentSource.APP);
            assertThat(saved0.getRecordedAt()).isNotNull();
        });
        assertThat(saved).extracting(ConsentRecord::getConsentKey)
                .containsExactly(
                        ConsentKey.PRIVACY_PERSONAL,
                        ConsentKey.LOCATION,
                        ConsentKey.LOCATION_NOTICE_BATCHED);
        assertThat(saved).extracting(ConsentRecord::isGranted).containsExactly(true, true, false);
        assertThat(response.items()).hasSize(3);
        assertThat(response.memberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    @DisplayName("같은 항목을 다시 제출하면 행이 쌓이고 현재 상태는 최신 행의 값을 반환한다")
    void 같은_항목을_다시_제출하면_현재_상태는_최신_값이다() {
        // given
        LocalDateTime first = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime second = LocalDateTime.of(2026, 9, 21, 11, 0);
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of(
                        record(ConsentKey.LOCATION, false, "app-consent-v2", second),
                        record(ConsentKey.LOCATION, true, VERSION, first)));

        // when
        ConsentStateResponse response = consentService.submit(
                MEMBER_ID, ConsentSubmitRequest.of("app-consent-v2", Map.of("LOCATION", false)));

        // then
        verify(consentRecordRepository).saveAll(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(1);
        assertThat(response.items()).hasSize(1);
        ConsentItemResponse item = response.items().get(0);
        assertThat(item.key()).isEqualTo(ConsentKey.LOCATION);
        assertThat(item.granted()).isFalse();
        assertThat(item.version()).isEqualTo("app-consent-v2");
        assertThat(item.recordedAt()).isEqualTo(second);
    }

    @Test
    @DisplayName("동의를 철회하면 직전 판을 복사한 철회 행이 남는다")
    void 동의를_철회하면_직전_판을_복사한_철회_행이_남는다() {
        // given
        LocalDateTime grantedAt = LocalDateTime.of(2026, 9, 21, 10, 0);
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of(record(ConsentKey.LOCATION, true, VERSION, grantedAt)));

        // when
        consentService.withdraw(MEMBER_ID, ConsentWithdrawRequest.of(List.of("LOCATION")));

        // then
        verify(consentRecordRepository).saveAll(recordsCaptor.capture());
        List<ConsentRecord> saved = recordsCaptor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getConsentKey()).isEqualTo(ConsentKey.LOCATION);
        assertThat(saved.get(0).isGranted()).isFalse();
        assertThat(saved.get(0).getVersion()).isEqualTo(VERSION);
        assertThat(saved.get(0).getSource()).isEqualTo(ConsentSource.APP);
    }

    @Test
    @DisplayName("철회 행이 최신이면 동의 여부는 거짓을 반환한다")
    void 철회_행이_최신이면_동의_여부는_거짓이다() {
        // given
        given(consentRecordRepository
                .findFirstByMemberIdAndConsentKeyOrderByRecordedAtDescIdDesc(
                        MEMBER_ID, ConsentKey.LOCATION))
                .willReturn(Optional.of(record(
                        ConsentKey.LOCATION, false, VERSION, LocalDateTime.of(2026, 9, 21, 11, 0))));

        // when
        boolean granted = consentService.isGranted(MEMBER_ID, ConsentKey.LOCATION);

        // then
        assertThat(granted).isFalse();
    }

    @Test
    @DisplayName("기록이 한 번도 없는 항목의 동의 여부는 거짓을 반환한다")
    void 기록이_없는_항목의_동의_여부는_거짓이다() {
        // given
        given(consentRecordRepository
                .findFirstByMemberIdAndConsentKeyOrderByRecordedAtDescIdDesc(
                        MEMBER_ID, ConsentKey.PRIVACY_HEALTH))
                .willReturn(Optional.empty());

        // when
        boolean granted = consentService.isGranted(MEMBER_ID, ConsentKey.PRIVACY_HEALTH);

        // then
        assertThat(granted).isFalse();
    }

    @Test
    @DisplayName("직전 기록이 없는 항목을 철회하면 판을 자리표시자로 남긴다")
    void 직전_기록이_없는_항목을_철회하면_판을_자리표시자로_남긴다() {
        // given
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of());

        // when
        consentService.withdraw(
                MEMBER_ID, ConsentWithdrawRequest.of(List.of("GUARDIAN_LOCATION_PROVIDE")));

        // then
        verify(consentRecordRepository).saveAll(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue().get(0).getVersion()).isEqualTo("-");
        assertThat(recordsCaptor.getValue().get(0).isGranted()).isFalse();
    }

    @Test
    @DisplayName("모르는 동의 항목을 제출하면 CONSENT_KEY_INVALID 예외를 던진다")
    void 모르는_동의_항목을_제출하면_예외가_발생한다() {
        // given
        ConsentSubmitRequest request =
                ConsentSubmitRequest.of(VERSION, Map.of("MARKETING_PUSH", true));

        // when & then
        assertThatThrownBy(() -> consentService.submit(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_KEY_INVALID);
    }

    @Test
    @DisplayName("모르는 동의 항목을 철회하면 CONSENT_KEY_INVALID 예외를 던진다")
    void 모르는_동의_항목을_철회하면_예외가_발생한다() {
        // given
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of());
        ConsentWithdrawRequest request = ConsentWithdrawRequest.of(List.of("MARKETING_PUSH"));

        // when & then
        assertThatThrownBy(() -> consentService.withdraw(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_KEY_INVALID);
    }

    @Test
    @DisplayName("동의 항목이 비어 있으면 CONSENT_REQUEST_EMPTY 예외를 던진다")
    void 동의_항목이_비어_있으면_예외가_발생한다() {
        // given
        ConsentSubmitRequest request = ConsentSubmitRequest.of(VERSION, Map.of());

        // when & then
        assertThatThrownBy(() -> consentService.submit(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUEST_EMPTY);
    }

    @Test
    @DisplayName("철회할 항목이 비어 있으면 CONSENT_REQUEST_EMPTY 예외를 던진다")
    void 철회할_항목이_비어_있으면_예외가_발생한다() {
        // given
        ConsentWithdrawRequest request = ConsentWithdrawRequest.of(List.of());

        // when & then
        assertThatThrownBy(() -> consentService.withdraw(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUEST_EMPTY);
    }

    @Test
    @DisplayName("관리자가 이력을 조회하면 전체 행을 최근순으로 반환하고 이름·전화번호를 싣지 않는다")
    void 관리자가_이력을_조회하면_전체_행을_최근순으로_반환한다() {
        // given
        LocalDateTime grantedAt = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime withdrawnAt = LocalDateTime.of(2026, 9, 21, 11, 30);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of(
                        record(ConsentKey.LOCATION, false, VERSION, withdrawnAt),
                        record(ConsentKey.LOCATION, true, VERSION, grantedAt)));

        // when
        List<ConsentRecordResponse> history = consentService.historyForAdmin(MEMBER_ID);

        // then
        assertThat(history).hasSize(2);
        assertThat(history).extracting(ConsentRecordResponse::recordedAt)
                .containsExactly(withdrawnAt, grantedAt);
        assertThat(history).extracting(ConsentRecordResponse::granted).containsExactly(false, true);
        assertThat(history.get(0).source()).isEqualTo(ConsentSource.APP);

        List<String> fields = java.util.Arrays.stream(ConsentRecordResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(fields).containsExactly("key", "granted", "version", "recordedAt", "source");
    }

    @Test
    @DisplayName("존재하지 않는 회원의 이력을 조회하면 MEMBER_NOT_FOUND 예외를 던진다")
    void 존재하지_않는_회원의_이력을_조회하면_예외가_발생한다() {
        // given
        given(memberRepository.existsById(anyLong())).willReturn(false);

        // when & then
        assertThatThrownBy(() -> consentService.historyForAdmin(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }
}
