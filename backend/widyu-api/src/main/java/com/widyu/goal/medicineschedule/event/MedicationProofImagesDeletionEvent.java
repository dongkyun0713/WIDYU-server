package com.widyu.goal.medicineschedule.event;

import java.util.List;
public record MedicationProofImagesDeletionEvent(List<Long> taskIds) {

    public MedicationProofImagesDeletionEvent {
        taskIds = taskIds.stream().filter(java.util.Objects::nonNull).toList();
    }
}
