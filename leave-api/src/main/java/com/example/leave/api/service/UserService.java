package com.example.leave.api.service;

import com.example.leave.api.entity.Employee;
import com.example.leave.api.repository.LeaveRepository;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ユーザー（社員）に関すること。「誰か」は社員マスタの id（= トークンの {@code preferred_username}）で決まり、
 * このアプリでの権限（ロール）も社員マスタから決める。IdP（トークン）のロールは使わない。
 */
@Service
public class UserService {

    public static final String ROLE_EMPLOYEE = "employee";
    public static final String ROLE_MANAGER = "manager";

    private final LeaveRepository leaveRepository;

    UserService(LeaveRepository leaveRepository) {
        this.leaveRepository = leaveRepository;
    }

    /** トークンの主体を社員マスタで引く。Keycloak にいても社員マスタにいなければ 403。 */
    public Employee employee(String username) {
        return leaveRepository.findEmployee(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "社員マスタに登録されていないユーザーです: " + username));
    }

    /**
     * このアプリでの権限（ロール）を社員マスタから決める。トークンのロールは見ない。
     *
     * <ul>
     *   <li>{@code employee}: 社員マスタにいる</li>
     *   <li>{@code manager}: 部下が1人以上いる（社員マスタの {@code managerId} で自分を指す社員がいる）</li>
     * </ul>
     * 社員マスタにいなければ空（どの API も呼べない）。
     */
    public Set<String> roles(String username) {
        if (username == null) {
            return Set.of();
        }
        return leaveRepository.findEmployee(username)
            .map(me -> leaveRepository.findSubordinates(me.id()).isEmpty()
                ? Set.of(ROLE_EMPLOYEE)
                : Set.of(ROLE_EMPLOYEE, ROLE_MANAGER))
            .orElse(Set.of());
    }

    public String managerName(Employee employee) {
        return employee.managerId() == null ? null
            : leaveRepository.findEmployee(employee.managerId()).map(Employee::name).orElse(null);
    }
}
