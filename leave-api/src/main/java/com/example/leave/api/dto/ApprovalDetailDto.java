package com.example.leave.api.dto;

import java.util.List;

/** 承認画面用。同じ期間に休む同じ部署の人を添える。 */
public record ApprovalDetailDto(LeaveRequestDto request, List<String> colleaguesOff) {
}
