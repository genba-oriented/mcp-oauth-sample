package com.example.leave.api.entity;

import java.time.LocalDate;

/** 有給の付与日数。今期分だけを持つ（期の繰り越しはデモの範囲外）。 */
public record LeaveGrant(String employeeId, double granted, LocalDate expiresOn) {
}
