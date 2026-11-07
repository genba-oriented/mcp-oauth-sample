package com.example.leave.api.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveRequest(
        long id,
        String employeeId,
        LeaveType type,
        LocalDate startDate,
        LocalDate endDate,
        DayPart dayPart,
        double days,
        String reason,
        LeaveStatus status,
        String approverId,
        String approverComment,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {

    public LeaveRequest decided(LeaveStatus newStatus, String comment, LocalDateTime at) {
        return new LeaveRequest(id, employeeId, type, startDate, endDate, dayPart, days, reason,
            newStatus, approverId, comment, createdAt, at);
    }

    public boolean overlaps(LocalDate from, LocalDate to) {
        return !endDate.isBefore(from) && !startDate.isAfter(to);
    }

    public boolean active() {
        return status == LeaveStatus.PENDING || status == LeaveStatus.APPROVED;
    }
}
