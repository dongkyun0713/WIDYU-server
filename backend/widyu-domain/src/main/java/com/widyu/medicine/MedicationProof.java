package com.widyu.medicine;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "medication_proof",
        // 회원별 날짜 조회(일자별·월별 현황, 홈)는 복용 기준일(verified_date)로 거른다.
        indexes = @Index(name = "idx_medication_proof_member_verified_date",
                columnList = "member_id, verified_date"),
        // 같은 날 동일 스케줄 중복 인증 차단 (애플리케이션 검사만으로는 동시 요청을 막지 못한다)
        uniqueConstraints = @UniqueConstraint(name = "uk_medication_proof_schedule_date",
                columnNames = {"medicine_schedule_id", "verified_date"})
)
public class MedicationProof extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medicine_schedule_id", nullable = false)
    private MedicineSchedule medicineSchedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ElementCollection
    @CollectionTable(name = "medication_proof_images",
                    joinColumns = @JoinColumn(name = "medication_proof_id"))
    @Column(name = "image_url")
    private List<String> proofImageUrls = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime verifiedAt;

    // 복용 기준일: 인증한 알람의 날짜(LLD-0101). 알람 ±30분 창이 자정을 넘으면 verifiedAt의 날짜와 하루 다르다.
    // 일자 단위 중복 차단 unique 제약과 날짜별 집계가 이 값을 쓴다.
    @Column(name = "verified_date", nullable = false)
    private LocalDate verifiedDate;

    @Builder(access = AccessLevel.PRIVATE)
    private MedicationProof(MedicineSchedule medicineSchedule, Member member,
                            List<String> proofImageUrls, LocalDateTime verifiedAt, LocalDate verifiedDate) {
        this.medicineSchedule = medicineSchedule;
        this.member = member;
        if (proofImageUrls != null) {
            this.proofImageUrls = proofImageUrls;
        } else {
            this.proofImageUrls = new ArrayList<>();
        }
        this.verifiedAt = verifiedAt;
        this.verifiedDate = verifiedDate;
    }

    public static MedicationProof create(MedicineSchedule medicineSchedule, Member member, List<String> proofImageUrls,
                                         LocalDateTime verifiedAt, LocalDate verifiedDate) {
        return MedicationProof.builder()
                .medicineSchedule(medicineSchedule)
                .member(member)
                .proofImageUrls(proofImageUrls)
                .verifiedAt(verifiedAt)
                .verifiedDate(verifiedDate)
                .build();
    }

    public void moveTo(MedicineSchedule medicineSchedule) {
        this.medicineSchedule = medicineSchedule;
    }
}
