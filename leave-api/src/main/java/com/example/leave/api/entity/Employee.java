package com.example.leave.api.entity;

/**
 * 社員マスタ。id は Keycloak の username（トークンの preferred_username）。
 * managerId は直属の上長。null なら承認者なし。
 */
public record Employee(String id, String name, String department, String managerId) {
}
