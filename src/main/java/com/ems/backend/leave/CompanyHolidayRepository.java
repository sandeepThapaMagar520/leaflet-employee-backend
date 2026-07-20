package com.ems.backend.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CompanyHolidayRepository extends JpaRepository<CompanyHoliday, LocalDate> {
    boolean existsByDateAndActiveTrue(LocalDate date);
    List<CompanyHoliday> findByActiveTrueAndDateBetween(LocalDate start, LocalDate end);
}
