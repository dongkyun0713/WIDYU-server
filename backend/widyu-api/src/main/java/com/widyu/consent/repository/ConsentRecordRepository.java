package com.widyu.consent.repository;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.ConsentRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 추가 전용 테이블이라 조회만 정의한다(LLD-0055 4절). 회원당 항목 6개에 수십 행이므로
 * 현재 상태도 전부 읽어 자바에서 접는다(LLD-0055 5절).
 *
 * <p>정렬에 {@code IdDesc}를 덧붙인 것은 같은 마이크로초에 들어온 제출·철회가
 * {@code recorded_at}만으로는 순서가 갈리지 않기 때문이다. 나중에 저장된 행이 최신이다.
 */
@Repository
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    List<ConsentRecord> findAllByMemberIdOrderByRecordedAtDescIdDesc(Long memberId);

    Optional<ConsentRecord> findFirstByMemberIdAndConsentKeyOrderByRecordedAtDescIdDesc(
            Long memberId, ConsentKey consentKey);
}
