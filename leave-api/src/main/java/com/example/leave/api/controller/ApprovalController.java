package com.example.leave.api.controller;

import com.example.leave.api.dto.ApprovalDetailDto;
import com.example.leave.api.dto.LeaveRequestDto;
import com.example.leave.api.entity.LeaveStatus;
import com.example.leave.api.input.DecisionInput;
import com.example.leave.api.service.LeaveService;
import com.example.leave.api.service.UserService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 承認（S5・S6）。{@code /approvals/**} は SecurityConfig で manager ロールに限定している。 */
@RestController
@RequestMapping("/approvals")
class ApprovalController {

    private final LeaveService leaveService;
    private final UserService userService;

    ApprovalController(LeaveService leaveService, UserService userService) {
        this.leaveService = leaveService;
        this.userService = userService;
    }

    @GetMapping
    List<LeaveRequestDto> list(@RequestParam(defaultValue = "PENDING") LeaveStatus status,
                               Authentication authentication) {
        return leaveService.approvals(userService.employee(authentication.getName()), status);
    }

    @GetMapping("/{id}")
    ApprovalDetailDto get(@PathVariable long id, Authentication authentication) {
        return leaveService.approval(userService.employee(authentication.getName()), id);
    }

    @PostMapping("/{id}/approve")
    LeaveRequestDto approve(@PathVariable long id, @RequestBody(required = false) DecisionInput decision,
                            Authentication authentication) {
        return leaveService.decide(userService.employee(authentication.getName()), id, LeaveStatus.APPROVED,
            decision == null ? null : decision.comment());
    }

    @PostMapping("/{id}/reject")
    LeaveRequestDto reject(@PathVariable long id, @RequestBody(required = false) DecisionInput decision,
                           Authentication authentication) {
        return leaveService.decide(userService.employee(authentication.getName()), id, LeaveStatus.REJECTED,
            decision == null ? null : decision.comment());
    }
}
