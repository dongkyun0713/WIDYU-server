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
import jakarta.persistence.PrePersist;
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
        indexes = @Index(name = "idx_medication_proof_member_verified",
                columnList = "member_id, verified_at"),
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

    // verifiedAt의 일자 부분. datetime으로는 일자 단위 unique 제약을 걸 수 없어 따로 둔다.
    @Column(name = "verified_date", nullable = false)
    private LocalDate verifiedDate;

    @Builder(access = AccessLevel.PRIVATE)
    private MedicationProof(MedicineSchedule medicineSchedule, Member member,
                            List<String> proofImageUrls, LocalDateTime verifiedAt) {
        this.medicineSchedule = medicineSchedule;
        this.member = member;
        if (proofImageUrls != null) {
            this.proofImageUrls = proofImageUrls;
        } else {
            this.proofImageUrls = new ArrayList<>();
        }
        if (verifiedAt != null) {
            this.verifiedAt = verifiedAt;
        } else {
            this.verifiedAt = LocalDateTime.now();
        }
    }

    @PrePersist
    private void applyVerifiedDate() {
        this.verifiedDate = this.verifiedAt.toLocalDate();
    }

    public static MedicationProof create(MedicineSchedule medicineSchedule, Member member,
                                         List<String> proofImageUrls) {
        return MedicationProof.builder()
                .medicineSchedule(medicineSchedule)
                .member(member)
                .proofImageUrls(proofImageUrls)
                .verifiedAt(LocalDateTime.now())
                .build();
    }
}
