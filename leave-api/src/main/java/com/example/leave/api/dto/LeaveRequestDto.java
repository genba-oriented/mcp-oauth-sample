package com.example.leave.api.dto;

import com.example.leave.api.entity.DayPart;
import com.example.leave.api.entity.LeaveStatus;
import com.example.leave.api.entity.LeaveType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 申請の表示用。名前を解決済みで返す。remainingDays は申請者の現在の残日数。 */
public record LeaveRequestDto(
        long id,
        String employeeId,
        String employeeName,
        String department,
        LeaveType type,
        LocalDate startDate,
        LocalDate endDate,
        DayPart dayPart,
        double days,
        String reason,
        LeaveStatus status,
        String approverId,
        String approverName,
        String approverComment,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        double remainingDays) {
}
