package com.admtechhub.maestrohr.document;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** JSON view of an onboarding checklist item. */
@Data
@AllArgsConstructor
public class OnboardingTaskDTO {
    private UUID id;
    private String taskName;
    private int taskOrder;
    private boolean completed;
    private OffsetDateTime completedAt;

    public static OnboardingTaskDTO from(OnboardingTask t) {
        return new OnboardingTaskDTO(t.getId(), t.getTaskName(), t.getTaskOrder(),
                t.isCompleted(), t.getCompletedAt());
    }
}
