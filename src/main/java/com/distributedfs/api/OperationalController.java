package com.distributedfs.api;

import com.distributedfs.api.dto.HealthResponse;
import com.distributedfs.api.dto.VersionResponse;
import com.distributedfs.service.OperationalStatusService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class OperationalController {

    private final OperationalStatusService operationalStatusService;

    public OperationalController(OperationalStatusService operationalStatusService) {
        this.operationalStatusService = operationalStatusService;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return HealthResponse.fromHealth(operationalStatusService.health());
    }

    @GetMapping(value = "/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionResponse version() {
        return VersionResponse.fromVersion(operationalStatusService.version());
    }
}
