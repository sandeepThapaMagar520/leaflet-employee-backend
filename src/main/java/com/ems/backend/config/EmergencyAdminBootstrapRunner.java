package com.ems.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmergencyAdminBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EmergencyAdminBootstrapRunner.class);

    private final EmergencyAdminBootstrapService bootstrapService;

    public EmergencyAdminBootstrapRunner(EmergencyAdminBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        EmergencyAdminBootstrapService.BootstrapResult result = bootstrapService.bootstrapIfEnabled();
        if (result == EmergencyAdminBootstrapService.BootstrapResult.CREATED) {
            log.warn(
                    "One-time emergency administrator bootstrap completed. Remove the bootstrap environment variables and complete account setup immediately."
            );
        } else if (result == EmergencyAdminBootstrapService.BootstrapResult.ALREADY_COMPLETED) {
            log.warn(
                    "Emergency administrator bootstrap is still enabled but was already consumed. Remove its environment variables."
            );
        }
    }
}

