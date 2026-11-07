package com.example.leave.api.controller;

import com.example.leave.api.dto.MeDto;
import com.example.leave.api.entity.Employee;
import com.example.leave.api.service.LeaveService;
import com.example.leave.api.service.UserService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** S1 ホーム。自分のプロフィールと残日数。 */
@RestController
class MeController {

    private final LeaveService leaveService;
    private final UserService userService;

    MeController(LeaveService leaveService, UserService userService) {
        this.leaveService = leaveService;
        this.userService = userService;
    }

    @GetMapping("/me")
    MeDto me(Authentication authentication) {
        Employee me = userService.employee(authentication.getName());
        List<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring("ROLE_".length()))
            .toList();
        return new MeDto(me.id(), me.name(), me.department(), userService.managerName(me), roles,
            leaveService.balance(me.id()));
    }
}
