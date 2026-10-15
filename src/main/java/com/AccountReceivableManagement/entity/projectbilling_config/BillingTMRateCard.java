package com.AccountReceivableManagement.entity.projectbilling_config;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.BillingRatePeriod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "billing_tm_rate_card")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingTMRateCard{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "rate_card_id")
    private UUID rateCardId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "billing_configuration_id",
            referencedColumnName = "billing_configuration_id",
            nullable = false
    )
    private BillingConfiguration billingConfiguration;

    @Column(name = "role_name", length = 100)
    private String roleName;

    @Column(name = "rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_period", nullable = false, length = 20)
    private BillingRatePeriod ratePeriod;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /**
     * Whether work billed at this card's base rate (no discount period applies on the work date) is billed.
     * Independent of {@code includeInInvoice} on each discount period. Null (rows created before this
     * column existed) means included.
     */
    @Column(name = "include_base_rate_in_invoice")
    private Boolean includeBaseRateInInvoice;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
