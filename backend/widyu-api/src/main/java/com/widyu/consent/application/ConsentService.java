package com.widyu.consent.application;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.ConsentRecord;
import com.widyu.consent.ConsentSource;
import com.widyu.consent.dto.request.ConsentSubmitRequest;
import com.widyu.consent.dto.request.ConsentWithdrawRequest;
import com.widyu.consent.dto.response.ConsentRecordResponse;
import com.widyu.consent.dto.response.ConsentStateResponse;
import com.widyu.consent.repository.ConsentRecordRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인앱 동의의 제출·철회·현재 상태를 다룬다(LLD-0055 5절, ADR-0036 결정 1).
 *
 * <p>행을 고치거나 지우지 않는다. 같은 값을 다시 제출해도 행을 남긴다 —
 * 「언제 다시 동의했는지」도 남겨야 할 사실이다.
 *
 * <p>동의 유무로 기능을 막지 않는다(ADR-0036 결정 2). {@link #isGranted}는 그 판단이 필요한
 * 다른 기능이 읽어 가는 창구일 뿐이다.
 *
 * <p>로그에는 회원 식별자와 항목 수만 남긴다. 이름·전화번호·건강값은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    /** 철회하려는 항목의 직전 기록이 없을 때 쓰는 자리표시 판(LLD-0055 5절). */
    private static final String UNKNOWN_VERSION = "-";

    private final ConsentRecordRepository consentRecordRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public ConsentStateResponse submit(Long memberId, ConsentSubmitRequest request) {
        Map<String, Boolean> consents = request.consents();
        if (consents == null || consents.isEmpty()) {
            throw new BusinessException(ErrorCode.CONSENT_REQUEST_EMPTY);
        }

        LocalDateTime recordedAt = LocalDateTime.now();
        List<ConsentRecord> records = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : consents.entrySet()) {
            ConsentKey key = toConsentKey(entry.getKey());
            Boolean granted = entry.getValue();
            if (granted == null) {
                throw new BusinessException(ErrorCode.CONSENT_REQUEST_EMPTY);
            }
            records.add(ConsentRecord.of(
                    memberId, key, request.version(), granted, recordedAt, ConsentSource.APP));
        }

        consentRecordRepository.saveAll(records);
        log.info("인앱 동의 제출 memberId={} itemCount={}", memberId, records.size());
        return currentState(memberId);
    }

    @Transactional
    public ConsentStateResponse withdraw(Long memberId, ConsentWithdrawRequest request) {
        List<String> keys = request.keys();
        if (keys == null || keys.isEmpty()) {
            throw new BusinessException(ErrorCode.CONSENT_REQUEST_EMPTY);
        }

        Map<ConsentKey, ConsentRecord> latest = latestByKey(readAll(memberId));
        LocalDateTime recordedAt = LocalDateTime.now();
        List<ConsentRecord> records = new ArrayList<>();
        for (String rawKey : keys) {
            ConsentKey key = toConsentKey(rawKey);
            records.add(ConsentRecord.of(
                    memberId, key, versionOf(latest.get(key)), false, recordedAt, ConsentSource.APP));
        }

        consentRecordRepository.saveAll(records);
        log.info("인앱 동의 철회 memberId={} itemCount={}", memberId, records.size());
        return currentState(memberId);
    }

    @Transactional(readOnly = true)
    public ConsentStateResponse currentState(Long memberId) {
        List<ConsentRecord> latest = List.copyOf(latestByKey(readAll(memberId)).values());
        return ConsentStateResponse.of(memberId, latest);
    }

    /** 최신 행이 동의일 때만 true다. 기록이 한 번도 없으면 false다(LLD-0055 5절). */
    @Transactional(readOnly = true)
    public boolean isGranted(Long memberId, ConsentKey key) {
        return consentRecordRepository
                .findFirstByMemberIdAndConsentKeyOrderByRecordedAtDescIdDesc(memberId, key)
                .map(ConsentRecord::isGranted)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<ConsentRecordResponse> historyForAdmin(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return readAll(memberId).stream().map(ConsentRecordResponse::from).toList();
    }

    private List<ConsentRecord> readAll(Long memberId) {
        return consentRecordRepository.findAllByMemberIdOrderByRecordedAtDescIdDesc(memberId);
    }

    /** 최근순으로 읽은 행에서 항목마다 처음 만난 행이 최신 행이다. */
    private Map<ConsentKey, ConsentRecord> latestByKey(List<ConsentRecord> records) {
        Map<ConsentKey, ConsentRecord> latest = new LinkedHashMap<>();
        for (ConsentRecord record : records) {
            latest.putIfAbsent(record.getConsentKey(), record);
        }
        return latest;
    }

    private String versionOf(ConsentRecord previous) {
        if (previous == null) {
            return UNKNOWN_VERSION;
        }
        return previous.getVersion();
    }

    private ConsentKey toConsentKey(String rawKey) {
        for (ConsentKey key : ConsentKey.values()) {
            if (key.name().equals(rawKey)) {
                return key;
            }
        }
        throw new BusinessException(ErrorCode.CONSENT_KEY_INVALID);
    }
}
