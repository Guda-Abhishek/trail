package com.AccountReceivableManagement.mapper.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto.ExpenseLineItemDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto.TimesheetLineItemDto;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps a persisted {@link BillingSnapshot} to the API response contract.
 * The snapshot only stores master-table ids, not names, so the Billing
 * Configuration read alongside it during acquisition supplies the display
 * names. A future read path that reconstructs a snapshot without a
 * freshly-fetched configuration (e.g. {@code GET /billing-snapshots/{id}})
 * will need to resolve those names from Epic 1's master data by id instead.
 *
 * Also maps the persisted {@link BillingSnapshotItem} lines into
 * {@link TimesheetLineItemDto}s so the frontend can render the Labor Charges
 * Preview table immediately from the creation response.
 */
@Component
public class BillingSnapshotMapper {

    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public BillingSnapshotResponseDto toResponse(BillingSnapshot snapshot, BillingConfigurationResponseDto configuration) {

        List<TimesheetLineItemDto> timesheetLineItems = snapshot.getItems().stream()
                // Labor Charges only; XMS expense lines are not timesheets.
                .filter(item -> item.getItemType() != com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType.EXPENSE)
                .map(this::toTimesheetLineItemDto)
                .collect(Collectors.toList());

        return BillingSnapshotResponseDto.builder()
                .snapshotId(snapshot.getId())
                .acquisitionStatus("READY")
                .snapshotNumber(snapshot.getSnapshotNumber())
                .projectId(snapshot.getProjectId())
                .clientId(snapshot.getClientId())
                .billingTypeId(snapshot.getBillingTypeId())
                .billingTypeName(configuration.getBillingTypeName())
                .currencyId(snapshot.getCurrencyId())
                .currencyCode(configuration.getCurrencyCode())
                .paymentTermId(snapshot.getPaymentTermId())
                .paymentTermName(configuration.getPaymentTermName())
                .billingFrequencyId(snapshot.getBillingFrequencyId())
                .billingFrequencyName(configuration.getBillingFrequencyName())
                .taxRegionId(snapshot.getTaxRegionId())
                .taxRegionCode(configuration.getTaxRegionCode())
                .sourceTaxJurisdictionCode(snapshot.getSourceTaxJurisdictionCode())
                .destinationTaxJurisdictionCode(snapshot.getDestinationTaxJurisdictionCode())
                .billingPeriodStart(snapshot.getBillingPeriodStart())
                .billingPeriodEnd(snapshot.getBillingPeriodEnd())
                .subtotal(snapshot.getSubtotal())
                .expenseAmount(snapshot.getExpenseAmount())
                .totalAmount(snapshot.getTotalAmount())
                .status(snapshot.getStatus())
                .timesheets(timesheetLineItems)
                .expenses(snapshot.getItems().stream()
                        .filter(item -> item.getItemType() == BillingItemType.EXPENSE)
                        .map(item -> toExpenseLineItemDto(item, snapshot.getCurrencyCode()))
                        .collect(Collectors.toList()))
                .build();
    }

    private ExpenseLineItemDto toExpenseLineItemDto(BillingSnapshotItem item, String snapshotCurrency) {
        // Everything here is read back from what was persisted at acquisition - amounts are never
        // recalculated or reconverted while mapping. Unreadable metadata only costs the optional
        // display fields, never the line itself.
        com.fasterxml.jackson.databind.JsonNode meta = readMetadata(item.getSourceMetadata());
        boolean converted = item.getExchangeRate() != null;
        String metaCurrency = text(meta, "currency");
        // A persisted conversion fixes the billed currency; otherwise it is what XMS supplied.
        String currency = converted && item.getTargetCurrency() != null ? item.getTargetCurrency()
                : metaCurrency != null ? metaCurrency : snapshotCurrency;
        return ExpenseLineItemDto.builder()
                .sourceReferenceId(item.getSourceReferenceId())
                .lineItemId(item.getSourceReferenceId())
                .expenseDate(item.getWorkDate())
                .category(item.getItemName())
                .description(text(meta, "description"))
                .reportNumber(text(meta, "reportNumber"))
                .currency(currency)
                .costBasis(item.getAmount())
                .sourceSystem(item.getSourceSystem())
                .syncId(item.getSourceEventId())
                .handedOffAt(dateTime(meta, "handedOffAt"))
                .handoffRemarks(text(meta, "handoffRemarks"))
                .supersededSyncIds(textList(meta, "supersededSyncIds"))
                .originalCurrency(text(meta, "originalCurrency"))
                .originalAmount(decimal(meta, "originalAmount"))
                .conversionApplied(converted)
                .sourceAmount(item.getSourceAmount())
                .sourceCurrency(item.getSourceCurrency())
                .exchangeRate(item.getExchangeRate())
                .exchangeRateDate(item.getExchangeRateDate())
                .exchangeRateSource(item.getExchangeRateSource())
                .build();
    }

    private static java.time.LocalDateTime dateTime(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : java.time.LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static java.math.BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).isNumber()
                ? node.get(field).decimalValue() : null;
    }

    private static List<String> textList(com.fasterxml.jackson.databind.JsonNode node, String field) {
        List<String> values = new java.util.ArrayList<>();
        if (node != null && node.hasNonNull(field) && node.get(field).isArray()) {
            node.get(field).forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private static com.fasterxml.jackson.databind.JsonNode readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return METADATA_READER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private TimesheetLineItemDto toTimesheetLineItemDto(BillingSnapshotItem item) {
        return TimesheetLineItemDto.builder()
                .employee(item.getItemName())               // itemName = resourceName
                .sourceReferenceId(item.getSourceReferenceId())
                .workDate(item.getWorkDate())
                .hours(item.getQuantity())                  // quantity = hours
                .rate(item.getRate())
                .amount(item.getAmount())
                .approvalStatus(item.getApprovalStatus())
                .role(item.getRole())
                .baseRate(item.getBaseRate())
                .currencyCode(item.getRateCurrency())
                .ratePeriod(item.getRatePeriod())
                .discountType(item.getDiscountType())
                .discountValue(item.getDiscountValue())
                .discountAmount(item.getDiscountAmount())
                .discountPeriodName(item.getDiscountPeriodName())
                .discountPeriodStart(item.getDiscountPeriodStart())
                .discountPeriodEnd(item.getDiscountPeriodEnd())
                .includeInInvoice(com.AccountReceivableManagement.util.TmRateCalculator
                        .isIncluded(item.getIncludeInInvoice()))
                .build();
    }
}

