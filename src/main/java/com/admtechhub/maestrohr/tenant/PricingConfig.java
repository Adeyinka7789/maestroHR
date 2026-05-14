package com.admtechhub.maestrohr.tenant;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pricing_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingConfig extends BaseEntity {

    @Column(name = "plan_name", nullable = false, length = 50)
    private String planName;

    @Column(name = "period", nullable = false, length = 20)
    private String period;  // MONTHLY, QUARTERLY, ANNUALLY

    @Column(name = "price_kobo", nullable = false)
    private Long priceKobo;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}