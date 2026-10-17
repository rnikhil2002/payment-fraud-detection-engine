package com.nikhilr.fraud.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A single payment coming off the "payments" Kafka topic.
 * <p>
 * CARD and UPI payments go to a merchant ({@code merchantId}); TRANSFER moves money
 * between two accounts ({@code toAccount}). {@code timestamp} is epoch millis.
 */
public record PaymentEvent(
        @NotBlank String txId,
        @NotNull PaymentType type,
        @NotBlank String fromAccount,
        String toAccount,
        String merchantId,
        @NotBlank String deviceId,
        String ipAddress,
        @Positive double amount,
        String currency,
        String country,
        long timestamp) {

    public boolean isTransfer() {
        return type == PaymentType.TRANSFER && toAccount != null && !toAccount.isBlank();
    }

    public boolean hasMerchant() {
        return merchantId != null && !merchantId.isBlank();
    }

    /** Returns a copy with the timestamp filled in when the producer did not send one. */
    public PaymentEvent withTimestampIfMissing(long now) {
        if (timestamp > 0) {
            return this;
        }
        return new PaymentEvent(txId, type, fromAccount, toAccount, merchantId, deviceId,
                ipAddress, amount, currency, country, now);
    }
}
