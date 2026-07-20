package com.ems.backend.leave;

import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.user.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;

@Service
public class LeaveBalanceService {
    public record PeriodBalance(LocalDate periodStart, LocalDate periodEnd, int entitlement, int used, int remaining) {}

    private final LeaveRequestRepository repository;
    private final LeaveDayCalculator dayCalculator;
    private final AppSettingsService settings;

    public LeaveBalanceService(
            LeaveRequestRepository repository,
            LeaveDayCalculator dayCalculator,
            AppSettingsService settings
    ) {
        this.repository = repository;
        this.dayCalculator = dayCalculator;
        this.settings = settings;
    }

    public PeriodBalance balance(User user, LeaveType type, LocalDate date) {
        LocalDate start = periodStart(date);
        LocalDate end = start.plusYears(1).minusDays(1);
        int entitlement = Math.max(baseEntitlement(type) + adjustment(user, type), 0);
        int used = repository.findApprovedOverlapping(user.getId(), type, start, end).stream()
                .mapToInt(leave -> dayCalculator.count(
                        leave.getStartDate().isBefore(start) ? start : leave.getStartDate(),
                        leave.getEndDate().isAfter(end) ? end : leave.getEndDate()
                ))
                .sum();
        return new PeriodBalance(start, end, entitlement, used, Math.max(entitlement - used, 0));
    }

    public void requireAvailableForApproval(User user, LeaveType type, LocalDate start, LocalDate end) {
        for (LocalDate cursor = periodStart(start); !cursor.isAfter(end); cursor = cursor.plusYears(1)) {
            PeriodBalance balance = balance(user, type, cursor);
            LocalDate sliceStart = start.isAfter(balance.periodStart()) ? start : balance.periodStart();
            LocalDate sliceEnd = end.isBefore(balance.periodEnd()) ? end : balance.periodEnd();
            int requested = dayCalculator.count(sliceStart, sliceEnd);
            if (requested > balance.remaining()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Insufficient " + type.name().toLowerCase() + " leave balance for period "
                                + balance.periodStart() + " to " + balance.periodEnd()
                );
            }
        }
    }

    private LocalDate periodStart(LocalDate date) {
        int resetMonth = settings.leaveResetMonth();
        LocalDate candidate = MonthDay.of(resetMonth, 1).atYear(date.getYear());
        return date.isBefore(candidate) ? candidate.minusYears(1) : candidate;
    }

    private int baseEntitlement(LeaveType type) {
        return switch (type) {
            case ANNUAL -> settings.annualLeaveDays();
            case SICK -> settings.sickLeaveDays();
            default -> 0;
        };
    }

    private int adjustment(User user, LeaveType type) {
        Integer value = switch (type) {
            case ANNUAL -> user.getLeaveBalanceAdjustmentDays();
            case SICK -> user.getSickLeaveBalanceAdjustmentDays();
            default -> 0;
        };
        return value != null ? value : 0;
    }
}
