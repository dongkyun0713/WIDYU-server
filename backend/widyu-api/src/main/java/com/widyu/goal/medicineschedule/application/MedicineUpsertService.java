package com.widyu.goal.medicineschedule.application;

import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.medicine.Medicine;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공공 API 약품의 신규 저장. 검색·배치와 다른 빈이라 이 트랜잭션은 외부 API 호출을 감싸지 않고,
 * itemSeq 유일 제약 위반이 나면 이 트랜잭션만 롤백된다(LLD-0100).
 */
@Service
@RequiredArgsConstructor
public class MedicineUpsertService {

    private final MedicineRepository medicineRepository;

    /**
     * 이미 존재하는 itemSeq는 건너뛰고 신규만 저장
     */
    @Transactional
    public List<Medicine> upsertMedicines(List<MedicineApiResponse.MedicineItem> apiItems) {
        List<String> seqs = apiItems.stream()
                .filter(item -> item.itemSeq() != null)
                .map(MedicineApiResponse.MedicineItem::itemSeq)
                .collect(Collectors.toList());

        Set<String> existingSeqs = medicineRepository.findItemSeqsByItemSeqIn(seqs);

        Set<String> seenSeqs = new HashSet<>();
        List<Medicine> toSave = apiItems.stream()
                .filter(item -> item.itemSeq() != null
                        && !existingSeqs.contains(item.itemSeq())
                        && seenSeqs.add(item.itemSeq()))
                .map(item -> Medicine.create(
                        item.itemSeq(),
                        item.itemName(),
                        item.entpName(),
                        item.itemImage(),
                        item.useMethodQesitm(),
                        item.efcyQesitm()
                ))
                .collect(Collectors.toList());

        if (!toSave.isEmpty()) {
            return medicineRepository.saveAll(toSave);
        }

        return List.of();
    }
}
