package com.example.leave.api.dto;

import java.util.List;

/** GET /me。上長かどうかは roles で SPA に伝える（メニューの出し分け用）。 */
public record MeDto(String id, String name, String department, String managerName,
                    List<String> roles, BalanceDto balance) {
}
