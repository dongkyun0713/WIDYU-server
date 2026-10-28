package com.widyu.goal.medicineschedule.repository;

import com.widyu.medicine.Medicine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    @Query(value = "SELECT * FROM medicine WHERE MATCH(item_name) AGAINST(:keyword IN BOOLEAN MODE) LIMIT 10", nativeQuery = true)
    List<Medicine> searchByNameFullText(@Param("keyword") String keyword);

    @Query(value = "SELECT * FROM medicine WHERE item_name LIKE :prefix LIMIT 10", nativeQuery = true)
    List<Medicine> searchByNamePrefix(@Param("prefix") String prefix);

    @Query("SELECT m.itemSeq FROM Medicine m WHERE m.itemSeq IN :seqs")
    Set<String> findItemSeqsByItemSeqIn(@Param("seqs") List<String> seqs);

    List<Medicine> findByItemSeqIn(Collection<String> itemSeqs);

    Optional<Medicine> findByItemName(String itemName);

    Optional<Medicine> findByItemSeq(String itemSeq);
}
