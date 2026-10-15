package com.AccountReceivableManagement.mapper.billing_data_acquisition;

import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingConfigurationResponseDto;
import com.AccountReceivableManagement.dto.billing_data_acquisition.BillingSnapshotResponseDto;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshot;
import com.AccountReceivableManagement.entity.billing_data_acquisition.BillingSnapshotItem;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingItemType;
import com.AccountReceivableManagement.entity_enums.billing_data_acquisition.BillingSnapshotStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage of {@link BillingSnapshotMapper#toResponse}, the step
 * that turns a persisted {@link BillingSnapshot}'s {@code items} into the
 * {@code timesheets} array of the API response.
 */
class BillingSnapshotMapperTest {

    private final BillingSnapshotMapper mapper = new BillingSnapshotMapper();

    @Test
    void toResponse_snapshotWithItems_mapsEachItemToATimesheetLineItem() {
        BillingSnapshotItem item1 = BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID())
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("Jane Doe")
                .sourceReferenceId("TMS-001")
                .quantity(BigDecimal.valueOf(8))
                .rate(BigDecimal.valueOf(165))
                .amount(BigDecimal.valueOf(1320))
                .workDate(LocalDate.of(2026, 8, 10))
                .approvalStatus("APPROVED")
                .role("Developer")
                .build();

        BillingSnapshotItem item2 = BillingSnapshotItem.builder()
                .billingSnapshotItemId(UUID.randomUUID())
                .itemType(BillingItemType.TIME_ENTRY)
                .itemName("John Smith")
                .sourceReferenceId("TMS-002")
                .quantity(BigDecimal.valueOf(4))
                .rate(BigDecimal.valueOf(150))
                .amount(BigDecimal.valueOf(600))
                .workDate(LocalDate.of(2026, 8, 11))
                .approvalStatus("APPROVED")
                .role("Tester")
                .build();

        BillingSnapshot snapshot = BillingSnapshot.builder()
                .id(UUID.randomUUID())
                .snapshotNumber("BS-20260810120000")
                .status(BillingSnapshotStatus.READY_FOR_TAX)
                .totalAmount(BigDecimal.valueOf(1920))
                .items(List.of(item1, item2))
                .build();

        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder()
                .billingTypeName("Timesheet Based")
                .currencyCode("USD")
                .build();

        BillingSnapshotResponseDto response = mapper.toResponse(snapshot, configuration);

        assertThat(response.getTimesheets()).hasSize(2);

        BillingSnapshotResponseDto.TimesheetLineItemDto mapped1 = response.getTimesheets().get(0);
        assertThat(mapped1.getEmployee()).isEqualTo("Jane Doe");
        assertThat(mapped1.getSourceReferenceId()).isEqualTo("TMS-001");
        assertThat(mapped1.getWorkDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(mapped1.getHours()).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(mapped1.getRate()).isEqualByComparingTo(BigDecimal.valueOf(165));
        assertThat(mapped1.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1320));
        assertThat(mapped1.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(mapped1.getRole()).isEqualTo("Developer");

        BillingSnapshotResponseDto.TimesheetLineItemDto mapped2 = response.getTimesheets().get(1);
        assertThat(mapped2.getEmployee()).isEqualTo("John Smith");
        assertThat(mapped2.getSourceReferenceId()).isEqualTo("TMS-002");
    }

    @Test
    void toResponse_snapshotWithNoItems_returnsEmptyTimesheetsList() {
        BillingSnapshot snapshot = BillingSnapshot.builder()
                .id(UUID.randomUUID())
                .snapshotNumber("BS-20260810120000")
                .status(BillingSnapshotStatus.READY_FOR_TAX)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BillingConfigurationResponseDto configuration = BillingConfigurationResponseDto.builder().build();

        BillingSnapshotResponseDto response = mapper.toResponse(snapshot, configuration);

        assertThat(response.getTimesheets()).isNotNull();
        assertThat(response.getTimesheets()).isEmpty();
    }

    // ---- XMS expenses ------------------------------------------------

    private BillingSnapshot snapshotWith(List<BillingSnapshotItem> items) {
        return BillingSnapshot.builder().id(UUID.randomUUID()).snapshotNumber("BS-1")
                .currencyCode("USD").status(BillingSnapshotStatus.READY_FOR_TAX)
                .subtotal(BigDecimal.valueOf(1320)).expenseAmount(new BigDecimal("50.00"))
                .totalAmount(new BigDecimal("1370.00")).items(items).build();
    }

    @Test
    void toResponse_expenseItems_areExposedSeparatelyFromTimesheetsWithAuditFields() {
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto expense =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L1").category("Travel").description("Taxi to client")
                        .reportNumber("R-9").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("50.00")).currency("USD")
                        .grossAmount(new BigDecimal("60.00")).taxCode("GST18").build();
        BillingSnapshotItem labor = BillingSnapshotItem.builder().itemType(BillingItemType.TIME_ENTRY)
                .itemName("Jane Doe").sourceReferenceId("TMS-1").quantity(BigDecimal.valueOf(8))
                .rate(BigDecimal.valueOf(165)).amount(BigDecimal.valueOf(1320)).build();
        // Real builder output, so the metadata JSON round-trips exactly as persisted.
        List<BillingSnapshotItem> items = new java.util.ArrayList<>(
                new com.AccountReceivableManagement.builder.billing_data_acquisition.BillingSnapshotBuilder()
                        .buildItems(List.of(), List.of(expense)));
        items.add(0, labor);

        BillingSnapshotResponseDto response = mapper.toResponse(snapshotWith(items),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build());

        assertThat(response.getTimesheets()).hasSize(1);
        assertThat(response.getTimesheets().get(0).getSourceReferenceId()).isEqualTo("TMS-1");
        assertThat(response.getExpenses()).hasSize(1);
        BillingSnapshotResponseDto.ExpenseLineItemDto dto = response.getExpenses().get(0);
        assertThat(dto.getSourceReferenceId()).isEqualTo("XMS-L1");
        assertThat(dto.getExpenseDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(dto.getCategory()).isEqualTo("Travel");
        assertThat(dto.getDescription()).isEqualTo("Taxi to client");
        assertThat(dto.getReportNumber()).isEqualTo("R-9");
        assertThat(dto.getCurrency()).isEqualTo("USD");
        assertThat(dto.getCostBasis()).isEqualByComparingTo("50.00");
        assertThat(dto.getSourceSystem()).isEqualTo("XMS");
        assertThat(response.getExpenseAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1370.00");
    }

    @Test
    void toResponse_convertedExpense_exposesBilledAmountInSnapshotCurrencyAndTheConversionAudit() {
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto converted =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L9").category("Hotel").expenseDate(LocalDate.of(2026, 8, 12))
                        .costBasis(new BigDecimal("5.00")).currency("USD")
                        .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR")
                        .exchangeRate(new BigDecimal("0.01250000")).exchangeRateDate(LocalDate.of(2026, 8, 12))
                        .exchangeRateSource("TEST-REF").build();

        List<BillingSnapshotItem> items = new com.AccountReceivableManagement.builder.billing_data_acquisition
                .BillingSnapshotBuilder().buildItems(List.of(), List.of(converted));

        // Persisted item: "amount" is the billed amount, the originals sit in their own columns.
        BillingSnapshotItem item = items.get(0);
        assertThat(item.getAmount()).isEqualByComparingTo("5.00");
        assertThat(item.getSourceAmount()).isEqualByComparingTo("400.0000");
        assertThat(item.getSourceCurrency()).isEqualTo("INR");
        assertThat(item.getTargetCurrency()).isEqualTo("USD");
        assertThat(item.getExchangeRate()).isEqualByComparingTo("0.0125");
        assertThat(item.getExchangeRateDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(item.getExchangeRateSource()).isEqualTo("TEST-REF");

        BillingSnapshotResponseDto.ExpenseLineItemDto dto = mapper.toResponse(
                snapshotWith(new java.util.ArrayList<>(items)),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build()).getExpenses().get(0);
        assertThat(dto.getCurrency()).isEqualTo("USD");
        assertThat(dto.getCostBasis()).isEqualByComparingTo("5.00");
        assertThat(dto.getSourceAmount()).isEqualByComparingTo("400.0000");
        assertThat(dto.getSourceCurrency()).isEqualTo("INR");
        assertThat(dto.getExchangeRate()).isEqualByComparingTo("0.0125");
        assertThat(dto.getExchangeRateSource()).isEqualTo("TEST-REF");
    }

    @Test
    void toResponse_sameCurrencyExpense_hasNoConversionAuditAndNoTargetCurrency() {
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto same =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L1").category("Travel").costBasis(new BigDecimal("50.00"))
                        .currency("USD").build();
        BillingSnapshotItem item = new com.AccountReceivableManagement.builder.billing_data_acquisition
                .BillingSnapshotBuilder().buildItems(List.of(), List.of(same)).get(0);

        assertThat(item.getSourceAmount()).isNull();
        assertThat(item.getSourceCurrency()).isNull();
        assertThat(item.getTargetCurrency()).isNull();
        assertThat(item.getExchangeRate()).isNull();
        assertThat(item.getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void toResponse_expenseBilledFromACorrectionHandoff_exposesTheEventIdentityAndReason() {
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto corrected =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L5").category("Hotel").costBasis(new BigDecimal("90.00")).currency("USD")
                        .syncId("S2").handedOffAt(java.time.LocalDateTime.of(2026, 8, 12, 10, 0))
                        .handoffRemarks("amount corrected").supersededSyncIds(List.of("S1")).build();

        BillingSnapshotItem item = new com.AccountReceivableManagement.builder.billing_data_acquisition
                .BillingSnapshotBuilder().buildItems(List.of(), List.of(corrected)).get(0);

        assertThat(item.getSourceEventId()).isEqualTo("S2");
        assertThat(item.getSourceReferenceId()).isEqualTo("XMS-L5");
        assertThat(item.getSourceMetadata()).contains("amount corrected").contains("S1");
        BillingSnapshotResponseDto.ExpenseLineItemDto dto = mapper.toResponse(
                snapshotWith(new java.util.ArrayList<>(List.of(item))),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build()).getExpenses().get(0);
        assertThat(dto.getSyncId()).isEqualTo("S2");
        assertThat(dto.getHandoffRemarks()).isEqualTo("amount corrected");
        assertThat(dto.getCostBasis()).isEqualByComparingTo("90.00");
    }

    @Test
    void toResponse_exposesEveryPersistedExpenseFactAndReadsAreStable() {
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto line =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L7").category("Hotel").description("for stay").reportNumber("R-7")
                        .expenseDate(LocalDate.of(2026, 8, 12)).costBasis(new BigDecimal("5.00")).currency("USD")
                        .originalCurrency("EUR").originalAmount(new BigDecimal("4.10"))
                        .syncId("S2").handedOffAt(java.time.LocalDateTime.of(2026, 8, 12, 10, 30, 15))
                        .handoffRemarks("amount corrected").supersededSyncIds(List.of("S1"))
                        .sourceAmount(new BigDecimal("400.0000")).sourceCurrency("INR")
                        .exchangeRate(new BigDecimal("0.01250000")).exchangeRateDate(LocalDate.of(2026, 8, 12))
                        .exchangeRateSource("TEST-REF").build();
        BillingSnapshotItem item = new com.AccountReceivableManagement.builder.billing_data_acquisition
                .BillingSnapshotBuilder().buildItems(List.of(), List.of(line)).get(0);
        BillingSnapshot snapshot = snapshotWith(new java.util.ArrayList<>(List.of(item)));
        BillingConfigurationResponseDto configuration =
                BillingConfigurationResponseDto.builder().currencyCode("USD").build();

        BillingSnapshotResponseDto.ExpenseLineItemDto first = mapper.toResponse(snapshot, configuration).getExpenses().get(0);
        BillingSnapshotResponseDto.ExpenseLineItemDto second = mapper.toResponse(snapshot, configuration).getExpenses().get(0);

        assertThat(first.getLineItemId()).isEqualTo("XMS-L7");
        assertThat(first.getSourceReferenceId()).isEqualTo("XMS-L7");
        assertThat(first.getSyncId()).isEqualTo("S2");
        assertThat(first.getExpenseDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(first.getHandedOffAt()).isEqualTo(java.time.LocalDateTime.of(2026, 8, 12, 10, 30, 15));
        assertThat(first.getHandoffRemarks()).isEqualTo("amount corrected");
        assertThat(first.getSupersededSyncIds()).containsExactly("S1");
        assertThat(first.getCostBasis()).isEqualByComparingTo("5.00");
        assertThat(first.getCurrency()).isEqualTo("USD");
        assertThat(first.getOriginalCurrency()).isEqualTo("EUR");
        assertThat(first.getOriginalAmount()).isEqualByComparingTo("4.10");
        assertThat(first.isConversionApplied()).isTrue();
        assertThat(first.getSourceAmount()).isEqualByComparingTo("400.0000");
        assertThat(first.getSourceCurrency()).isEqualTo("INR");
        assertThat(first.getExchangeRate()).isEqualByComparingTo("0.0125");
        assertThat(first.getExchangeRateDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(first.getExchangeRateSource()).isEqualTo("TEST-REF");
        // Same persisted facts on every read - nothing is recalculated or reconverted while mapping.
        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    void toResponse_expenseWithoutAPersistedConversion_isNeverReportedAsConverted_evenIfCurrenciesDiffer() {
        // Original (receipt) currency differs from the billed currency, but no conversion was persisted.
        com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto line =
                com.AccountReceivableManagement.dto.billing_data_acquisition.ExpenseLineDto.builder()
                        .lineItemId("XMS-L8").category("Taxi").costBasis(new BigDecimal("50.00")).currency("USD")
                        .originalCurrency("EUR").originalAmount(new BigDecimal("60.00")).syncId("S1").build();
        BillingSnapshotItem item = new com.AccountReceivableManagement.builder.billing_data_acquisition
                .BillingSnapshotBuilder().buildItems(List.of(), List.of(line)).get(0);

        BillingSnapshotResponseDto.ExpenseLineItemDto dto = mapper.toResponse(
                snapshotWith(new java.util.ArrayList<>(List.of(item))),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build()).getExpenses().get(0);

        assertThat(dto.isConversionApplied()).isFalse();
        assertThat(dto.getExchangeRate()).isNull();
        assertThat(dto.getSourceAmount()).isNull();
        assertThat(dto.getExchangeRateSource()).isNull();
        assertThat(dto.getOriginalCurrency()).isEqualTo("EUR");   // audit-only receipt facts stay as stored
        assertThat(dto.getCurrency()).isEqualTo("USD");
        assertThat(dto.getCostBasis()).isEqualByComparingTo("50.00");
        assertThat(dto.getSupersededSyncIds()).isEmpty();
        assertThat(dto.getHandedOffAt()).isNull();                // not stored -> not invented
    }

    @Test
    void toResponse_legacyExpenseItemWithoutMetadata_stillMapsWithoutInventingValues() {
        BillingSnapshotItem legacy = BillingSnapshotItem.builder().itemType(BillingItemType.EXPENSE)
                .itemName("Travel").sourceReferenceId("XMS-OLD").amount(new BigDecimal("5.00")).build();

        BillingSnapshotResponseDto.ExpenseLineItemDto dto = mapper.toResponse(
                snapshotWith(new java.util.ArrayList<>(List.of(legacy))),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build()).getExpenses().get(0);

        assertThat(dto.getLineItemId()).isEqualTo("XMS-OLD");
        assertThat(dto.getSyncId()).isNull();
        assertThat(dto.getHandedOffAt()).isNull();
        assertThat(dto.getOriginalAmount()).isNull();
        assertThat(dto.isConversionApplied()).isFalse();
        assertThat(dto.getCurrency()).isEqualTo("USD");   // falls back to the snapshot currency
        assertThat(dto.getSupersededSyncIds()).isEmpty();
    }

    @Test
    void toResponse_laborOnlySnapshot_hasEmptyExpensesArray_andUnreadableMetadataNeverDropsTheLine() throws Exception {
        BillingSnapshotResponseDto laborOnly = mapper.toResponse(snapshotWith(List.of()),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build());
        assertThat(laborOnly.getExpenses()).isEmpty();

        BillingSnapshotItem broken = BillingSnapshotItem.builder().itemType(BillingItemType.EXPENSE)
                .itemName("Travel").sourceReferenceId("XMS-L2").amount(new BigDecimal("5.00"))
                .sourceSystem("XMS").sourceMetadata("{not json").build();
        BillingSnapshotResponseDto response = mapper.toResponse(snapshotWith(List.of(broken)),
                BillingConfigurationResponseDto.builder().currencyCode("USD").build());
        assertThat(response.getExpenses()).hasSize(1);
        assertThat(response.getExpenses().get(0).getCurrency()).isEqualTo("USD");

        // JSON contract: "expenses" is always present as an array.
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        assertThat(json.readTree(json.writeValueAsString(laborOnly)).get("expenses").isArray()).isTrue();
    }
}
