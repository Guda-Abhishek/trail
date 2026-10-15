package com.AccountReceivableManagement.entity.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.DiscountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One dated discount period on a T&M rate card. The base rate stays on {@link BillingTMRateCard};
 * the currency and billing unit are those of the rate card / billing configuration.
 */
@Getter
@Setter
@Entity
@Table(name = "billing_tm_rate_discount",
        indexes = @Index(name = "idx_tm_rate_discount_card_dates",
                columnList = "rate_card_id, effective_from, effective_to"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingTMRateDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "discount_id")
    private UUID discountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_card_id", referencedColumnName = "rate_card_id", nullable = false)
    private BillingTMRateCard rateCard;

    @Column(name = "period_name", nullable = false, length = 150)
    private String periodName;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private LocalDate effectiveTo;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "include_in_invoice", nullable = false)
    private Boolean includeInInvoice;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
