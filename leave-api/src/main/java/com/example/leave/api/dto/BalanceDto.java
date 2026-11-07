package com.example.leave.api.dto;

import java.time.LocalDate;

/** 残日数 = 付与 − 取得済（承認済） − 申請中。 */
public record BalanceDto(double granted, double used, double pending, double remaining, LocalDate expiresOn) {
}
