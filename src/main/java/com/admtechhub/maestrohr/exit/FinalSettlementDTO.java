package com.admtechhub.maestrohr.exit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalSettlementDTO {
    private UUID id;
    private UUID exitRequestId;
    private BigDecimal unpaidSalary;
    private BigDecimal accruedLeave;
    private BigDecimal severancePay;
    private BigDecimal loanDeduction;
    private BigDecimal otherDeductions;
    private BigDecimal totalPayable;
    private String paymentStatus;
    private LocalDate paymentDate;
    private String notes;
}