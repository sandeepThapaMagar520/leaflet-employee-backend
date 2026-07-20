package com.ems.backend.leave;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LeaveDayCalculator {
    private final CompanyHolidayRepository holidayRepository;
    private final boolean excludeWeekends;

    public LeaveDayCalculator(
            CompanyHolidayRepository holidayRepository,
            @Value("${app.policy.leave.exclude-weekends:false}") boolean excludeWeekends
    ) {
        this.holidayRepository = holidayRepository;
        this.excludeWeekends = excludeWeekends;
    }

    public int count(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        Set<LocalDate> holidays = holidayRepository.findByActiveTrueAndDateBetween(start, end)
                .stream()
                .map(CompanyHoliday::getDate)
                .collect(Collectors.toSet());
        int days = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!holidays.contains(date) && (!excludeWeekends || !weekend)) {
                days++;
            }
        }
        return days;
    }

    public boolean isWorkingDay(LocalDate date) {
        if (holidayRepository.existsByDateAndActiveTrue(date)) {
            return false;
        }
        return !excludeWeekends
                || (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY);
    }
}
