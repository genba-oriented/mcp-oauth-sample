package com.example.leave.api.entity;

/** 申請中 → 承認済 / 却下。申請中のみ本人が取り消せる。 */
public enum LeaveStatus { PENDING, APPROVED, REJECTED, CANCELED }
