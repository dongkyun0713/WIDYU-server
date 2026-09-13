package com.widyu.goal.medicineschedule.event;

import java.util.List;

public record MedicationProofImagesDeletionEvent(Long memberId, List<String> imageUrls) {

    public MedicationProofImagesDeletionEvent {
        imageUrls = List.copyOf(imageUrls);
    }
}
