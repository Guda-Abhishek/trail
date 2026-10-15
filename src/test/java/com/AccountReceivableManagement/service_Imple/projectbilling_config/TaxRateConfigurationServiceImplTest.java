package com.AccountReceivableManagement.service_Imple.projectbilling_config;

import com.AccountReceivableManagement.dto.projectbilling_config.TaxConfigurationComponentRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.TaxConfigurationRequestDto;
import com.AccountReceivableManagement.dto.projectbilling_config.TaxConfigurationResponseDto;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxConfiguration;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxConfigurationComponent;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxRegionMaster;
import com.AccountReceivableManagement.entity.projectbilling_config.TaxTypeMaster;
import com.AccountReceivableManagement.entity_enums.tax_calculation.TaxApplicabilityType;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxConfigurationRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxRegionMasterRepository;
import com.AccountReceivableManagement.repo.projectbilling_config.TaxTypeMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxRateConfigurationServiceImplTest {

    @Mock
    private TaxConfigurationRepository configurationRepository;

    @Mock
    private TaxRegionMasterRepository taxRegionMasterRepository;

    @Mock
    private TaxTypeMasterRepository taxTypeMasterRepository;

    // TaxConfigurationServiceImpl resolves the legacy taxRegime string through this repository; an
    // unmatched code resolves to no regime master (Optional.empty), which is what these tests rely on.
    @Mock
    private com.AccountReceivableManagement.repo.projectbilling_config.TaxRegimeMasterRepository taxRegimeMasterRepository;

    @InjectMocks
    private TaxConfigurationServiceImpl taxConfigurationService;

    private TaxRegionMaster activeTaxRegion;

    private TaxTypeMaster activeTaxType;

    @BeforeEach
    void setUp() {
        activeTaxRegion = TaxRegionMaster.builder()
                .taxRegionId(UUID.randomUUID())
                .taxRegionCode("IN-KA")
                .taxRegionName("Karnataka")
                .taxRegime("GST")
                .currencyCode("INR")
                .isActive(true)
                .build();

        activeTaxType = TaxTypeMaster.builder()
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("GST")
                .taxTypeName("Goods and Services Tax")
                .isActive(true)
                .build();
    }

    private TaxConfigurationComponentRequestDto validComponentRequest() {
        return TaxConfigurationComponentRequestDto.builder()
                .taxTypeId(activeTaxType.getTaxTypeId())
                .taxRate(new BigDecimal("9.0000"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .build();
    }

    private TaxConfigurationRequestDto validRequest() {
        return TaxConfigurationRequestDto.builder()
                .taxRegionId(activeTaxRegion.getTaxRegionId())
                .taxRegime("GST")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveTo(null)
                .components(new ArrayList<>(List.of(validComponentRequest())))
                .build();
    }

    private TaxConfiguration existingConfiguration(UUID id) {
        TaxConfiguration configuration = TaxConfiguration.builder()
                .taxConfigurationId(id)
                .taxRegion(activeTaxRegion)
                .taxRegime("GST")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .isActive(true)
                .components(new ArrayList<>())
                .build();

        TaxConfigurationComponent component = TaxConfigurationComponent.builder()
                .taxConfigurationComponentId(UUID.randomUUID())
                .taxConfiguration(configuration)
                .taxType(activeTaxType)
                .taxRate(new BigDecimal("9.0000"))
                .applicabilityType(TaxApplicabilityType.ALL)
                .isActive(true)
                .build();

        configuration.getComponents().add(component);
        return configuration;
    }

    // 1. Create a valid configuration with one active component.
    @Test
    void create_validConfiguration_returnsSavedConfiguration() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(taxTypeMasterRepository.findById(activeTaxType.getTaxTypeId()))
                .thenReturn(Optional.of(activeTaxType));
        when(configurationRepository.save(any(TaxConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaxConfigurationResponseDto response = taxConfigurationService.create(validRequest());

        assertThat(response.getTaxRegionId()).isEqualTo(activeTaxRegion.getTaxRegionId());
        assertThat(response.getTaxRegionCode()).isEqualTo("IN-KA");
        assertThat(response.getTaxRegime()).isEqualTo("GST");
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getComponents()).hasSize(1);
        assertThat(response.getComponents().get(0).getTaxTypeCode()).isEqualTo("GST");
        assertThat(response.getComponents().get(0).getTaxRate()).isEqualByComparingTo("9.0000");
        verify(configurationRepository).save(any(TaxConfiguration.class));
    }

    // 2. Reject missing tax region.
    @Test
    void create_missingTaxRegion_throwsResourceNotFoundException() {
        UUID unknownRegionId = UUID.randomUUID();
        TaxConfigurationRequestDto request = validRequest();
        request.setTaxRegionId(unknownRegionId);

        when(taxRegionMasterRepository.findById(unknownRegionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Tax region not found.");

        verify(configurationRepository, never()).save(any());
    }

    // 3. Reject inactive tax region.
    @Test
    void create_inactiveTaxRegion_throwsValidationException() {
        TaxRegionMaster inactiveRegion = TaxRegionMaster.builder()
                .taxRegionId(UUID.randomUUID())
                .taxRegionCode("IN-MH")
                .taxRegionName("Maharashtra")
                .taxRegime("GST")
                .currencyCode("INR")
                .isActive(false)
                .build();

        TaxConfigurationRequestDto request = validRequest();
        request.setTaxRegionId(inactiveRegion.getTaxRegionId());

        when(taxRegionMasterRepository.findById(inactiveRegion.getTaxRegionId()))
                .thenReturn(Optional.of(inactiveRegion));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax region is inactive.");

        verify(configurationRepository, never()).save(any());
    }

    // 4. Reject invalid effective date range.
    @Test
    void create_invalidEffectiveDateRange_throwsValidationException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));

        TaxConfigurationRequestDto request = validRequest();
        request.setEffectiveFrom(LocalDate.of(2026, 4, 1));
        request.setEffectiveTo(LocalDate.of(2026, 3, 1));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Effective end date cannot be earlier than effective start date.");

        verify(configurationRepository, never()).save(any());
    }

    // 5. Reject a request with no tax components.
    @Test
    void create_missingComponents_throwsValidationException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(List.of());

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("At least one tax component is required.");

        verify(configurationRepository, never()).save(any());
    }

    // 6. Reject a negative tax rate on a component.
    @Test
    void create_negativeComponentTaxRate_throwsValidationException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(List.of(
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(activeTaxType.getTaxTypeId())
                        .taxRate(new BigDecimal("-1.00"))
                        .applicabilityType(TaxApplicabilityType.ALL)
                        .build()
        ));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax rate cannot be negative.");

        verify(configurationRepository, never()).save(any());
    }

    // 7. Reject the same tax type being configured more than once.
    @Test
    void create_duplicateTaxTypeInComponents_throwsValidationException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(List.of(validComponentRequest(), validComponentRequest()));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("A tax type cannot be configured more than once in the same configuration.");

        verify(configurationRepository, never()).save(any());
    }

    // 8. Detect conflicting/overlapping effective periods.
    @Test
    void create_overlappingEffectivePeriod_throwsValidationException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of(existingConfiguration(UUID.randomUUID())));

        assertThatThrownBy(() -> taxConfigurationService.create(validRequest()))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("An active tax configuration already exists for this tax region and effective period.");

        verify(configurationRepository, never()).save(any());
    }

    // 9. Reject a component referencing an unknown tax type.
    @Test
    void create_missingTaxType_throwsResourceNotFoundException() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(taxTypeMasterRepository.findById(activeTaxType.getTaxTypeId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxConfigurationService.create(validRequest()))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Tax type not found.");

        verify(configurationRepository, never()).save(any());
    }

    // 10. Reject a component referencing an inactive tax type.
    @Test
    void create_inactiveTaxType_throwsValidationException() {
        TaxTypeMaster inactiveTaxType = TaxTypeMaster.builder()
                .taxTypeId(activeTaxType.getTaxTypeId())
                .taxTypeCode("GST")
                .taxTypeName("Goods and Services Tax")
                .isActive(false)
                .build();

        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(taxTypeMasterRepository.findById(activeTaxType.getTaxTypeId()))
                .thenReturn(Optional.of(inactiveTaxType));

        assertThatThrownBy(() -> taxConfigurationService.create(validRequest()))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("Tax type is inactive.");

        verify(configurationRepository, never()).save(any());
    }

    // 11. Retrieve configuration by id.
    @Test
    void getById_existingConfiguration_returnsConfiguration() {
        UUID configId = UUID.randomUUID();
        TaxConfiguration configuration = existingConfiguration(configId);

        when(configurationRepository.findById(configId)).thenReturn(Optional.of(configuration));

        TaxConfigurationResponseDto response = taxConfigurationService.getById(configId);

        assertThat(response.getTaxConfigurationId()).isEqualTo(configId);
        assertThat(response.getTaxRegionCode()).isEqualTo("IN-KA");
        assertThat(response.getComponents()).hasSize(1);
    }

    @Test
    void getById_missingConfiguration_throwsResourceNotFoundException() {
        UUID configId = UUID.randomUUID();
        when(configurationRepository.findById(configId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxConfigurationService.getById(configId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Tax configuration not found.");
    }

    // 12. Retrieve active configurations.
    @Test
    void getActive_returnsOnlyActiveConfigurations() {
        TaxConfiguration active = existingConfiguration(UUID.randomUUID());

        when(configurationRepository.findByIsActiveTrueOrderByEffectiveFromDesc())
                .thenReturn(List.of(active));

        List<TaxConfigurationResponseDto> response = taxConfigurationService.getActive();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getIsActive()).isTrue();
    }

    // 13. Retrieve configurations by tax region.
    @Test
    void getByTaxRegion_returnsConfigurationsForRegion() {
        TaxConfiguration configuration = existingConfiguration(UUID.randomUUID());

        when(taxRegionMasterRepository.existsById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(true);
        when(configurationRepository
                .findByTaxRegion_TaxRegionIdAndIsActiveTrueOrderByEffectiveFromDesc(activeTaxRegion.getTaxRegionId()))
                .thenReturn(List.of(configuration));

        List<TaxConfigurationResponseDto> response =
                taxConfigurationService.getByTaxRegion(activeTaxRegion.getTaxRegionId());

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getTaxRegionId()).isEqualTo(activeTaxRegion.getTaxRegionId());
    }

    // 14. Reject retrieval for an unknown tax region.
    @Test
    void getByTaxRegion_missingRegion_throwsResourceNotFoundException() {
        UUID unknownRegionId = UUID.randomUUID();
        when(taxRegionMasterRepository.existsById(unknownRegionId)).thenReturn(false);

        assertThatThrownBy(() -> taxConfigurationService.getByTaxRegion(unknownRegionId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Tax region not found.");
    }

    // 15. Deactivate configuration.
    @Test
    void deactivate_existingConfiguration_setsIsActiveFalse() {
        UUID configId = UUID.randomUUID();
        TaxConfiguration configuration = existingConfiguration(configId);

        when(configurationRepository.findById(configId)).thenReturn(Optional.of(configuration));
        when(configurationRepository.save(any(TaxConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        taxConfigurationService.deactivate(configId);

        ArgumentCaptor<TaxConfiguration> captor = ArgumentCaptor.forClass(TaxConfiguration.class);
        verify(configurationRepository).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }

    @Test
    void deactivate_missingConfiguration_throwsResourceNotFoundException() {
        UUID configId = UUID.randomUUID();
        when(configurationRepository.findById(configId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxConfigurationService.deactivate(configId))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class)
                .hasMessage("Tax configuration not found.");

        verify(configurationRepository, never()).save(any());
    }

    // 16. Verify BigDecimal precision is preserved through the component mapping.
    @Test
    void create_preservesBigDecimalScaleOnComponents() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(taxTypeMasterRepository.findById(activeTaxType.getTaxTypeId()))
                .thenReturn(Optional.of(activeTaxType));
        when(configurationRepository.save(any(TaxConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(List.of(
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(activeTaxType.getTaxTypeId())
                        .taxRate(new BigDecimal("9.1234"))
                        .applicabilityType(TaxApplicabilityType.ALL)
                        .build()
        ));

        TaxConfigurationResponseDto response = taxConfigurationService.create(request);

        BigDecimal taxRate = response.getComponents().get(0).getTaxRate();
        assertThat(taxRate.scale()).isEqualTo(4);
        assertThat(taxRate).isEqualByComparingTo(new BigDecimal("9.1234"));
    }

    // ---------------------------------------------------------------
    // Duplicate / overlap, update and jurisdiction-applicability contract
    // ---------------------------------------------------------------

    // Open-ended create is compared against stored rows up to 9999-12-31, and the
    // tax regime plays no part in the duplicate check - only region + dates do.
    @Test
    void create_openEndedPeriod_overlapCheckUsesRegionAndOpenEndedDate() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of(existingConfiguration(UUID.randomUUID())));

        TaxConfigurationRequestDto request = validRequest();
        request.setTaxRegime("Sales Tax");
        request.setEffectiveFrom(LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("An active tax configuration already exists for this tax region and effective period.");

        verify(configurationRepository).findOverlappingConfigurations(
                activeTaxRegion.getTaxRegionId(),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(9999, 12, 31),
                null);
        verify(configurationRepository, never()).save(any());
    }

    // The existing configuration is updated in place: same id, own id excluded from
    // the overlap check, components replaced (not merged), and a DIFFERENT_JURISDICTION
    // component of a second tax type can sit beside the SAME_JURISDICTION one.
    @Test
    void update_addsDifferentJurisdictionComponent_keepsIdAndReplacesComponents() {
        UUID configurationId = UUID.randomUUID();
        TaxConfiguration existing = existingConfiguration(configurationId);
        existing.getComponents().get(0).setApplicabilityType(TaxApplicabilityType.SAME_JURISDICTION);

        TaxTypeMaster igst = TaxTypeMaster.builder()
                .taxTypeId(UUID.randomUUID())
                .taxTypeCode("IGST")
                .taxTypeName("Integrated GST")
                .isActive(true)
                .build();

        when(configurationRepository.findById(configurationId)).thenReturn(Optional.of(existing));
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(taxTypeMasterRepository.findById(activeTaxType.getTaxTypeId()))
                .thenReturn(Optional.of(activeTaxType));
        when(taxTypeMasterRepository.findById(igst.getTaxTypeId())).thenReturn(Optional.of(igst));
        when(configurationRepository.save(any(TaxConfiguration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(new ArrayList<>(List.of(
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(activeTaxType.getTaxTypeId())
                        .taxRate(new BigDecimal("9"))
                        .applicabilityType(TaxApplicabilityType.SAME_JURISDICTION)
                        .build(),
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(igst.getTaxTypeId())
                        .taxRate(new BigDecimal("18"))
                        .applicabilityType(TaxApplicabilityType.DIFFERENT_JURISDICTION)
                        .build())));

        TaxConfigurationResponseDto response = taxConfigurationService.update(configurationId, request);

        assertThat(response.getTaxConfigurationId()).isEqualTo(configurationId);
        assertThat(response.getComponents())
                .extracting(c -> c.getTaxTypeCode() + ":" + c.getApplicabilityType())
                .containsExactlyInAnyOrder("GST:SAME_JURISDICTION", "IGST:DIFFERENT_JURISDICTION");
        verify(configurationRepository).findOverlappingConfigurations(
                activeTaxRegion.getTaxRegionId(), LocalDate.of(2026, 4, 1),
                LocalDate.of(9999, 12, 31), configurationId);
    }

    // Updating cannot make a configuration overlap a different active one.
    @Test
    void update_overlappingAnotherConfiguration_throwsValidationException() {
        UUID configurationId = UUID.randomUUID();
        when(configurationRepository.findById(configurationId))
                .thenReturn(Optional.of(existingConfiguration(configurationId)));
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));
        when(configurationRepository.findOverlappingConfigurations(any(), any(), any(), any()))
                .thenReturn(List.of(existingConfiguration(UUID.randomUUID())));

        assertThatThrownBy(() -> taxConfigurationService.update(configurationId, validRequest()))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("An active tax configuration already exists for this tax region and effective period.");

        verify(configurationRepository, never()).save(any());
    }

    @Test
    void update_missingConfiguration_throwsResourceNotFoundException() {
        UUID configurationId = UUID.randomUUID();
        when(configurationRepository.findById(configurationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taxConfigurationService.update(configurationId, validRequest()))
                .isInstanceOf(GlobalExceptionHandler.ResourceNotFoundException.class);
    }

    // The same tax type cannot be listed twice, even for different applicability types.
    @Test
    void create_sameTaxTypeForTwoApplicabilities_isRejected() {
        when(taxRegionMasterRepository.findById(activeTaxRegion.getTaxRegionId()))
                .thenReturn(Optional.of(activeTaxRegion));

        TaxConfigurationRequestDto request = validRequest();
        request.setComponents(new ArrayList<>(List.of(
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(activeTaxType.getTaxTypeId())
                        .taxRate(new BigDecimal("9"))
                        .applicabilityType(TaxApplicabilityType.SAME_JURISDICTION)
                        .build(),
                TaxConfigurationComponentRequestDto.builder()
                        .taxTypeId(activeTaxType.getTaxTypeId())
                        .taxRate(new BigDecimal("18"))
                        .applicabilityType(TaxApplicabilityType.DIFFERENT_JURISDICTION)
                        .build())));

        assertThatThrownBy(() -> taxConfigurationService.create(request))
                .isInstanceOf(GlobalExceptionHandler.ValidationException.class)
                .hasMessage("A tax type cannot be configured more than once in the same configuration.");
    }
}
