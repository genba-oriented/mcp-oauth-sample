package com.example.leave.api.repository;

import com.example.leave.api.entity.DayPart;
import com.example.leave.api.entity.Employee;
import com.example.leave.api.entity.LeaveGrant;
import com.example.leave.api.entity.LeaveRequest;
import com.example.leave.api.entity.LeaveStatus;
import com.example.leave.api.entity.LeaveType;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.stereotype.Repository;

/**
 * インメモリの社員マスタと休暇申請。起動のたびに初期データに戻る。
 *
 * <p>初期データの日付は起動日からの相対で作る。いつデモしても「来週の申請中」「先月の承認済」が並ぶ。
 * 社員は Keycloak（keycloak/import/sample-corp-realm.json）のユーザーと username で対応する。
 */
@Repository
public class LeaveRepository {

    private final Map<String, Employee> employees = new LinkedHashMap<>();
    private final Map<String, LeaveGrant> grants = new LinkedHashMap<>();
    private final Map<Long, LeaveRequest> requests = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    LeaveRepository(Clock clock) {
        this.clock = clock;
        reset();
    }

    /** 初期データに戻す（申請番号も 1001 から振り直す）。テストが毎回呼ぶ。 */
    public synchronized void reset() {
        employees.clear();
        grants.clear();
        requests.clear();
        sequence.set(1000);

        LocalDate today = LocalDate.now(clock);
        LocalDate fiscalYearEnd = LocalDate.of(today.getMonthValue() >= 4 ? today.getYear() + 1 : today.getYear(), 3, 31);

        add(new Employee("sato", "佐藤 一郎", "営業部", null), 20, fiscalYearEnd);
        add(new Employee("yamada", "山田 太郎", "営業部", "sato"), 20, fiscalYearEnd);
        add(new Employee("suzuki", "鈴木 花子", "営業部", "sato"), 12, fiscalYearEnd);

        LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDateTime now = LocalDateTime.now(clock);

        // 山田: 承認済 2件、却下 1件、取消 1件、申請中 1件（来週月曜）
        seed("yamada", LeaveType.PAID, weekdayBefore(today, 40), DayPart.FULL, 2.0, "家族旅行",
            LeaveStatus.APPROVED, "楽しんできてください", now.minusDays(55));
        seed("yamada", LeaveType.PAID, weekdayBefore(today, 20), DayPart.PM, 0.5, "通院",
            LeaveStatus.APPROVED, null, now.minusDays(25));
        seed("yamada", LeaveType.PAID, weekdayBefore(today, 10), DayPart.FULL, 1.0, "私用のため",
            LeaveStatus.REJECTED, "月末締めの日なので別日でお願いします", now.minusDays(14));
        seed("yamada", LeaveType.PAID, weekdayBefore(today, 5), DayPart.FULL, 1.0, "私用のため",
            LeaveStatus.CANCELED, null, now.minusDays(9));
        seed("yamada", LeaveType.PAID, nextMonday, DayPart.FULL, 1.0, "私用のため",
            LeaveStatus.PENDING, null, now.minusDays(1));

        // 鈴木: 承認済 1件、申請中 1件（来週水曜・午前半休）
        seed("suzuki", LeaveType.PAID, weekdayBefore(today, 30), DayPart.FULL, 3.0, "帰省",
            LeaveStatus.APPROVED, null, now.minusDays(45));
        seed("suzuki", LeaveType.PAID, nextMonday.plusDays(2), DayPart.AM, 0.5, "役所の手続き",
            LeaveStatus.PENDING, null, now.minusDays(2));

        // 佐藤（上長）: 承認済 1件
        seed("sato", LeaveType.PAID, weekdayBefore(today, 15), DayPart.FULL, 1.0, "私用のため",
            LeaveStatus.APPROVED, null, now.minusDays(30));
    }

    private void add(Employee employee, double granted, LocalDate expiresOn) {
        employees.put(employee.id(), employee);
        grants.put(employee.id(), new LeaveGrant(employee.id(), granted, expiresOn));
    }

    /** days が 1.0 より大きい初期データは、start から平日を数えて end を決める。 */
    private void seed(String employeeId, LeaveType type, LocalDate start, DayPart dayPart, double days,
                      String reason, LeaveStatus status, String comment, LocalDateTime createdAt) {
        LocalDate end = start;
        for (int i = 1; i < days; i++) {
            end = nextWeekday(end);
        }
        String approverId = employees.get(employeeId).managerId();
        LocalDateTime decidedAt = status == LeaveStatus.APPROVED || status == LeaveStatus.REJECTED
            ? createdAt.plusDays(1) : null;
        long id = sequence.incrementAndGet();
        requests.put(id, new LeaveRequest(id, employeeId, type, start, end, dayPart, days, reason,
            status, approverId, comment, createdAt, decidedAt));
    }

    private static LocalDate weekdayBefore(LocalDate date, int daysAgo) {
        LocalDate d = date.minusDays(daysAgo);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    // ---- 参照・更新 -------------------------------------------------------------------------

    public Optional<Employee> findEmployee(String id) {
        return Optional.ofNullable(employees.get(id));
    }

    public List<Employee> findSubordinates(String managerId) {
        return employees.values().stream().filter(e -> managerId.equals(e.managerId())).toList();
    }

    public Optional<LeaveGrant> findGrant(String employeeId) {
        return Optional.ofNullable(grants.get(employeeId));
    }

    public synchronized Optional<LeaveRequest> findRequest(long id) {
        return Optional.ofNullable(requests.get(id));
    }

    /** 新しい順。 */
    public synchronized List<LeaveRequest> findRequests(Predicate<LeaveRequest> filter) {
        return new ArrayList<>(requests.values()).stream()
            .filter(filter)
            .sorted(Comparator.comparing(LeaveRequest::createdAt).reversed())
            .toList();
    }

    public synchronized LeaveRequest insert(LeaveRequest draft) {
        long id = sequence.incrementAndGet();
        LeaveRequest saved = new LeaveRequest(id, draft.employeeId(), draft.type(), draft.startDate(),
            draft.endDate(), draft.dayPart(), draft.days(), draft.reason(), draft.status(),
            draft.approverId(), draft.approverComment(), draft.createdAt(), draft.decidedAt());
        requests.put(id, saved);
        return saved;
    }

    public synchronized void update(LeaveRequest request) {
        requests.put(request.id(), request);
    }
}
