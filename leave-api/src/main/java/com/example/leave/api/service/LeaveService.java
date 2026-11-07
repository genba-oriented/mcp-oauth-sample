package com.example.leave.api.service;

import com.example.leave.api.dto.ApprovalDetailDto;
import com.example.leave.api.dto.BalanceDto;
import com.example.leave.api.dto.LeaveRequestDto;
import com.example.leave.api.entity.DayPart;
import com.example.leave.api.entity.Employee;
import com.example.leave.api.entity.LeaveGrant;
import com.example.leave.api.entity.LeaveRequest;
import com.example.leave.api.entity.LeaveStatus;
import com.example.leave.api.entity.LeaveType;
import com.example.leave.api.input.LeaveRequestInput;
import com.example.leave.api.repository.LeaveRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 休暇申請の業務ルール。
 *
 * <p>呼び出し元が SPA（BFF 経由）でも、後で足す MCP Server 経由でも、<b>同じルールで同じエラー</b>を返す。
 * 「誰として」は常にトークンの {@code preferred_username}（= 社員マスタの id）で決まり、
 * リクエストボディで他人を名乗ることはできない。
 */
@Service
public class LeaveService {

    private static final Set<DayOfWeek> WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final LeaveRepository leaveRepository;
    private final UserService userService;
    private final Clock clock;

    LeaveService(LeaveRepository leaveRepository, UserService userService, Clock clock) {
        this.leaveRepository = leaveRepository;
        this.userService = userService;
        this.clock = clock;
    }

    // ---- 残日数 ---------------------------------------------------------------------------

    public BalanceDto balance(String employeeId) {
        LeaveGrant grant = leaveRepository.findGrant(employeeId)
            .orElse(new LeaveGrant(employeeId, 0, null));
        List<LeaveRequest> paid = leaveRepository.findRequests(
            r -> r.employeeId().equals(employeeId) && r.type() == LeaveType.PAID);
        double used = sum(paid, LeaveStatus.APPROVED);
        double pending = sum(paid, LeaveStatus.PENDING);
        return new BalanceDto(grant.granted(), used, pending, grant.granted() - used - pending, grant.expiresOn());
    }

    private static double sum(List<LeaveRequest> requests, LeaveStatus status) {
        return requests.stream().filter(r -> r.status() == status).mapToDouble(LeaveRequest::days).sum();
    }

    // ---- 自分の申請 -------------------------------------------------------------------------

    public List<LeaveRequestDto> myRequests(Employee me) {
        return leaveRepository.findRequests(r -> r.employeeId().equals(me.id())).stream().map(this::view).toList();
    }

    /** 本人の申請か、自分が承認者の申請だけ見られる。それ以外は存在を明かさず 404。 */
    public LeaveRequestDto request(Employee me, long id) {
        LeaveRequest request = leaveRepository.findRequest(id)
            .filter(r -> r.employeeId().equals(me.id()) || me.id().equals(r.approverId()))
            .orElseThrow(() -> notFound(id));
        return view(request);
    }

    public synchronized LeaveRequestDto submit(Employee me, LeaveRequestInput input) {
        if (input == null || input.type() == null || input.startDate() == null) {
            throw badRequest("種別と休暇日は必須です");
        }
        LocalDate start = input.startDate();
        LocalDate end = input.endDate() != null ? input.endDate() : start;
        DayPart dayPart = input.dayPart() != null ? input.dayPart() : DayPart.FULL;
        LocalDate today = LocalDate.now(clock);

        if (start.isBefore(today)) {
            throw badRequest("休暇日は本日以降を指定してください");
        }
        if (end.isBefore(start)) {
            throw badRequest("終了日は開始日以降を指定してください");
        }
        if (dayPart != DayPart.FULL && !start.equals(end)) {
            throw badRequest("半休は1日だけ指定できます");
        }
        double days = countDays(start, end, dayPart);
        if (days == 0) {
            throw badRequest("指定した期間に平日がありません");
        }
        boolean overlapping = !leaveRepository.findRequests(
            r -> r.employeeId().equals(me.id()) && r.active() && r.overlaps(start, end)).isEmpty();
        if (overlapping) {
            throw badRequest("同じ日に申請中または承認済の休暇があります");
        }
        if (input.type() == LeaveType.PAID) {
            double remaining = balance(me.id()).remaining();
            if (days > remaining) {
                throw badRequest("有給の残日数が足りません（残 %.1f 日 / 申請 %.1f 日）".formatted(remaining, days));
            }
        }
        LeaveRequest saved = leaveRepository.insert(new LeaveRequest(0, me.id(), input.type(), start, end, dayPart, days,
            input.reason(), LeaveStatus.PENDING, me.managerId(), null, LocalDateTime.now(clock), null));
        return view(saved);
    }

    public LeaveRequestDto cancel(Employee me, long id) {
        LeaveRequest request = leaveRepository.findRequest(id)
            .filter(r -> r.employeeId().equals(me.id()))
            .orElseThrow(() -> notFound(id));
        if (request.status() != LeaveStatus.PENDING) {
            throw conflict("申請中の申請だけ取り消せます（現在: " + request.status() + "）");
        }
        LeaveRequest canceled = request.decided(LeaveStatus.CANCELED, null, LocalDateTime.now(clock));
        leaveRepository.update(canceled);
        return view(canceled);
    }

    // ---- 承認（上長） -------------------------------------------------------------------------

    /** 自分が承認者になっている申請。status で絞る（既定は申請中）。 */
    public List<LeaveRequestDto> approvals(Employee manager, LeaveStatus status) {
        return leaveRepository.findRequests(r -> manager.id().equals(r.approverId()) && r.status() == status)
            .stream().map(this::view).toList();
    }

    public ApprovalDetailDto approval(Employee manager, long id) {
        LeaveRequest request = approvable(manager, id);
        Employee applicant = userService.employee(request.employeeId());
        Set<String> sameDepartment = leaveRepository.findSubordinates(manager.id()).stream()
            .filter(e -> e.department().equals(applicant.department()) && !e.id().equals(applicant.id()))
            .map(Employee::id).collect(Collectors.toSet());
        List<String> colleaguesOff = leaveRepository.findRequests(
                r -> sameDepartment.contains(r.employeeId()) && r.active()
                    && r.overlaps(request.startDate(), request.endDate()))
            .stream().map(r -> userService.employee(r.employeeId()).name()).distinct().toList();
        return new ApprovalDetailDto(view(request), colleaguesOff);
    }

    public synchronized LeaveRequestDto decide(Employee manager, long id, LeaveStatus decision, String comment) {
        LeaveRequest request = approvable(manager, id);
        if (request.status() != LeaveStatus.PENDING) {
            throw conflict("申請中の申請だけ承認・却下できます（現在: " + request.status() + "）");
        }
        LeaveRequest decided = request.decided(decision, blankToNull(comment), LocalDateTime.now(clock));
        leaveRepository.update(decided);
        return view(decided);
    }

    /**
     * 自分が承認者の申請か。manager ロールを持っていても、他部署・他チームの申請は承認できない。
     * ロールは SecurityConfig、関係はここ、という分担。
     */
    private LeaveRequest approvable(Employee manager, long id) {
        return leaveRepository.findRequest(id)
            .filter(r -> manager.id().equals(r.approverId()))
            .orElseThrow(() -> notFound(id));
    }

    // ---- helpers --------------------------------------------------------------------------

    public static double countDays(LocalDate start, LocalDate end, DayPart dayPart) {
        if (dayPart != DayPart.FULL) {
            return WEEKEND.contains(start.getDayOfWeek()) ? 0 : 0.5;
        }
        return start.datesUntil(end.plusDays(1)).filter(d -> !WEEKEND.contains(d.getDayOfWeek())).count();
    }

    private LeaveRequestDto view(LeaveRequest r) {
        Employee applicant = userService.employee(r.employeeId());
        String approverName = r.approverId() == null ? null
            : leaveRepository.findEmployee(r.approverId()).map(Employee::name).orElse(null);
        return new LeaveRequestDto(r.id(), r.employeeId(), applicant.name(), applicant.department(), r.type(),
            r.startDate(), r.endDate(), r.dayPart(), r.days(), r.reason(), r.status(), r.approverId(), approverName,
            r.approverComment(), r.createdAt(), r.decidedAt(), balance(r.employeeId()).remaining());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "申請 #" + id + " が見つかりません");
    }
}
