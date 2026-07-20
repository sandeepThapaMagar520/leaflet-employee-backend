package com.ems.backend.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "company_holidays")
public class CompanyHoliday {
    @Id
    @Column(name = "holiday_date")
    private LocalDate date;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;
}
