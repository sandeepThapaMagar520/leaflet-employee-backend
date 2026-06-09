package com.ems.backend.dailylog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateDailyLogRequest {
    @NotNull(message = "Log date is required")
    private LocalDate logDate;

    @NotBlank(message = "Summary is required")
    private String summary;

    private String problemsFaced;
}
