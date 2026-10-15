package com.AccountReceivableManagement.util;

import com.AccountReceivableManagement.entity_enums.projectbilling_config.DiscountType;
import com.AccountReceivableManagement.global_exception_handler.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal-safe Time and Material rate arithmetic. Base rate, discount amount and net rate are all in the
 * billing configuration's currency and in the base rate's billing unit; no FX conversion happens here.
 * Amounts keep the 2-decimal scale of the persisted money columns, rounded HALF_UP.
 */
public final class TmRateCalculator {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TmRateCalculator() {
    }

    public record RateResult(BigDecimal baseRate, BigDecimal discountAmount, BigDecimal netRate) {
    }

    /** Net rate = base rate minus the discount; the result must stay greater than zero. */
    public static RateResult apply(BigDecimal baseRate, DiscountType type, BigDecimal value) {
        if (baseRate == null || baseRate.signum() <= 0) {
            throw new GlobalExceptionHandler.ValidationException("Base rate must be greater than zero.");
        }
        BigDecimal base = baseRate.setScale(SCALE, ROUNDING);
        if (type == null) {
            return new RateResult(base, BigDecimal.ZERO.setScale(SCALE), base);
        }
        if (value == null || value.signum() <= 0) {
            throw new GlobalExceptionHandler.ValidationException("Discount value must be greater than zero.");
        }
        BigDecimal discountAmount;
        if (type == DiscountType.PERCENTAGE) {
            if (value.compareTo(HUNDRED) >= 0) {
                throw new GlobalExceptionHandler.ValidationException(
                        "Discount percentage must be greater than 0 and less than 100.");
            }
            discountAmount = base.multiply(value).divide(HUNDRED, SCALE, ROUNDING);
        } else {
            discountAmount = value.setScale(SCALE, ROUNDING);
        }
        BigDecimal net = base.subtract(discountAmount);
        if (net.signum() <= 0) {
            throw new GlobalExceptionHandler.ValidationException(
                    "Discount must leave a net rate greater than zero (base rate " + base
                            + ", discount " + discountAmount + ").");
        }
        return new RateResult(base, discountAmount, net);
    }

    public static BigDecimal lineAmount(BigDecimal hours, BigDecimal netRate) {
        return hours.multiply(netRate).setScale(SCALE, ROUNDING);
    }

    /** A null flag (lines created before the flag existed, expense lines) means included. */
    public static boolean isIncluded(Boolean includeInInvoice) {
        return includeInInvoice == null || includeInInvoice;
    }
}
