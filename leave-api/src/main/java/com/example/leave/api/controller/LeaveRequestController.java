package com.example.leave.api.controller;

import com.example.leave.api.dto.LeaveRequestDto;
import com.example.leave.api.input.LeaveRequestInput;
import com.example.leave.api.service.LeaveService;
import com.example.leave.api.service.UserService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 自分の休暇申請（S2〜S4）。 */
@RestController
@RequestMapping("/leave-requests")
class LeaveRequestController {

    private final LeaveService leaveService;
    private final UserService userService;

    LeaveRequestController(LeaveService leaveService, UserService userService) {
        this.leaveService = leaveService;
        this.userService = userService;
    }

    @GetMapping
    List<LeaveRequestDto> list(Authentication authentication) {
        return leaveService.myRequests(userService.employee(authentication.getName()));
    }

    @GetMapping("/{id}")
    LeaveRequestDto get(@PathVariable long id, Authentication authentication) {
        return leaveService.request(userService.employee(authentication.getName()), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LeaveRequestDto submit(@RequestBody LeaveRequestInput input, Authentication authentication) {
        return leaveService.submit(userService.employee(authentication.getName()), input);
    }

    @PostMapping("/{id}/cancel")
    LeaveRequestDto cancel(@PathVariable long id, Authentication authentication) {
        return leaveService.cancel(userService.employee(authentication.getName()), id);
    }
}
