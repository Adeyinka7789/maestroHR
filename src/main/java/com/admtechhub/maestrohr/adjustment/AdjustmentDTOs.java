package com.admtechhub.maestrohr.adjustment;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response DTOs for the adjustments API (kept together — all small and cohesive). */
public final class AdjustmentDTOs {

    private AdjustmentDTOs() {
    }

    public record TypeView(UUID id, String name, String code, AdjustmentDirection direction,
                           AdjustmentTaxTreatment taxTreatment, boolean active, boolean system) {
        public static TypeView from(AdjustmentType t) {
            return new TypeView(t.getId(), t.getName(), t.getCode(), t.getDirection(),
                    t.getTaxTreatment(), t.isActive(), t.isSystem());
        }
    }

    public record CreateTypeRequest(String name, AdjustmentDirection direction,
                                    AdjustmentTaxTreatment taxTreatment) {
    }

    public record AdjustmentView(UUID id, UUID employeeId, String employeeName,
                                 UUID typeId, String typeName, AdjustmentDirection direction,
                                 AdjustmentTaxTreatment taxTreatment, long amountKobo,
                                 int periodMonth, int periodYear, String note,
                                 AdjustmentStatus status, String createdBy, OffsetDateTime createdAt) {
    }

    public record CreateAdjustmentRequest(UUID employeeId, UUID typeId, long amountKobo,
                                          int periodMonth, int periodYear, String note) {
    }
}
