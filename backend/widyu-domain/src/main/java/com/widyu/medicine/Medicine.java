package com.widyu.medicine;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Medicine extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 100)
    private String itemSeq;

    @Column(nullable = false, length = 200)
    private String itemName;

    @Column(length = 200)
    private String entpName;

    @Column(length = 500)
    private String itemImage;

    @Column(columnDefinition = "TEXT")
    private String useMethodQesitm;

    @Column(columnDefinition = "TEXT")
    private String efcyQesitm;

    @Builder(access = AccessLevel.PRIVATE)
    private Medicine(String itemSeq, String itemName, String entpName, String itemImage,
                     String useMethodQesitm, String efcyQesitm) {
        this.itemSeq = itemSeq;
        this.itemName = itemName;
        this.entpName = entpName;
        this.itemImage = itemImage;
        this.useMethodQesitm = useMethodQesitm;
        this.efcyQesitm = efcyQesitm;
    }

    public static Medicine create(String itemSeq, String itemName, String entpName, String itemImage,
                                   String useMethodQesitm, String efcyQesitm) {
        return Medicine.builder()
                .itemSeq(itemSeq)
                .itemName(itemName)
                .entpName(entpName)
                .itemImage(itemImage)
                .useMethodQesitm(useMethodQesitm)
                .efcyQesitm(efcyQesitm)
                .build();
    }

    // Getter 메서드 추가 (기존 코드와 호환성 유지)
    public String getName() {
        return itemName;
    }

    public String getImageUrl() {
        return itemImage;
    }

    public String getUsage() {
        return useMethodQesitm;
    }

    public String getEfficacy() {
        return efcyQesitm;
    }

    public String getDescription() {
        StringBuilder description = new StringBuilder();

        if (efcyQesitm != null && !efcyQesitm.isBlank()) {
            description.append(efcyQesitm);
        }

        if (useMethodQesitm != null && !useMethodQesitm.isBlank()) {
            if (description.length() > 0) {
                description.append("\n\n");
            }
            description.append(useMethodQesitm);
        }

        if (description.length() > 0) {
            return description.toString();
        }
        return null;
    }
}
