package com.ems.backend.leave;

import com.ems.backend.settings.AppSettingsService;
import com.ems.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaveBalanceServiceTest {
    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final CompanyHolidayRepository holidays = mock(CompanyHolidayRepository.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final LeaveDayCalculator dayCalculator = new LeaveDayCalculator(holidays, false);
    private final LeaveBalanceService service = new LeaveBalanceService(repository, dayCalculator, settings);

    @Test
    void crossYearUsageCountsOnlyDaysInsideCurrentResetPeriod() {
        User user = user();
        LeaveRequest approved = new LeaveRequest();
        approved.setStartDate(LocalDate.of(2025, 12, 30));
        approved.setEndDate(LocalDate.of(2026, 1, 3));
        when(settings.leaveResetMonth()).thenReturn(1);
        when(settings.annualLeaveDays()).thenReturn(21);
        when(repository.findApprovedOverlapping(
                eq(10L), eq(LeaveType.ANNUAL), any(LocalDate.class), any(LocalDate.class)
        )).thenReturn(List.of(approved));

        LeaveBalanceService.PeriodBalance balance =
                service.balance(user, LeaveType.ANNUAL, LocalDate.of(2026, 7, 20));

        assertEquals(LocalDate.of(2026, 1, 1), balance.periodStart());
        assertEquals(3, balance.used());
        assertEquals(18, balance.remaining());
    }

    @Test
    void approvalChecksEachResetPeriodIndependently() {
        User user = user();
        when(settings.leaveResetMonth()).thenReturn(1);
        when(settings.annualLeaveDays()).thenReturn(2);
        when(repository.findApprovedOverlapping(
                anyLong(), eq(LeaveType.ANNUAL), any(LocalDate.class), any(LocalDate.class)
        )).thenReturn(List.of());

        assertThrows(ResponseStatusException.class, () -> service.requireAvailableForApproval(
                user,
                LeaveType.ANNUAL,
                LocalDate.of(2025, 12, 30),
                LocalDate.of(2026, 1, 3)
        ));
    }

    private User user() {
        User user = new User();
        user.setId(10L);
        user.setLeaveBalanceAdjustmentDays(0);
        return user;
    }
}
