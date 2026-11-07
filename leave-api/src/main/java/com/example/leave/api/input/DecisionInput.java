package com.example.leave.api.input;

/** POST /approvals/{id}/approve・reject。comment は任意。 */
public record DecisionInput(String comment) {
}
