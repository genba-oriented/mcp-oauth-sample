package com.example.leave.api.input;

import com.example.leave.api.entity.DayPart;
import com.example.leave.api.entity.LeaveType;
import java.time.LocalDate;

/** POST /leave-requests。endDate・dayPart は省略可（1日・全日）。 */
public record LeaveRequestInput(LeaveType type, LocalDate startDate, LocalDate endDate,
                                DayPart dayPart, String reason) {
}
