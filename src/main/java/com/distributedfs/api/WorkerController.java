package com.distributedfs.api;

import com.distributedfs.api.dto.WorkerRunResponse;
import com.distributedfs.config.OpenApiConfiguration;
import com.distributedfs.error.ValidationException;
import com.distributedfs.service.BackgroundWorkerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for manually triggering maintenance workers.
 */
@RestController
@RequestMapping("/api/v1/workers")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
public class WorkerController {

    private final BackgroundWorkerService backgroundWorkerService;

    public WorkerController(BackgroundWorkerService backgroundWorkerService) {
        this.backgroundWorkerService = backgroundWorkerService;
    }

    @Operation(
        summary = "Scan replica metadata",
        description = "Checks recorded chunk replicas and removes metadata references for replicas that are missing or corrupted."
    )
    @PostMapping(value = "/scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkerRunResponse scanAndPrune() {
        int removedReferences = backgroundWorkerService.scanAndPruneMissingReplicas();
        return new WorkerRunResponse("scanAndPruneMissingReplicas", removedReferences);
    }

    @Operation(
        summary = "Repair under-replicated chunks",
        description = "Copies chunk data onto additional healthy nodes until the configured replication target is restored where possible."
    )
    @PostMapping(value = "/repair", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkerRunResponse repairUnderReplicatedChunks() {
        int repairedReplicas = backgroundWorkerService.repairUnderReplicatedChunks();
        return new WorkerRunResponse("repairUnderReplicatedChunks", repairedReplicas);
    }

    @Operation(
        summary = "Garbage collect deleted chunks",
        description = "Deletes unreferenced chunk data and purges chunk metadata records after the configured retention window."
    )
    @PostMapping(value = "/gc", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkerRunResponse garbageCollect(
        @RequestParam(value = "referenceTime", required = false) String referenceTime
    ) {
        Instant resolvedReferenceTime = parseReferenceTime(referenceTime);
        int removedChunks = backgroundWorkerService.garbageCollect(resolvedReferenceTime);
        return new WorkerRunResponse("garbageCollect", removedChunks);
    }

    @Operation(
        summary = "Migrate legacy local chunks",
        description = "This project initially leveraged local storage for chunk persistence, and this endpoint migrates those legacy local chunk files into the configured Oracle Object Storage backend, removing the local source after a successful write."
    )
    @PostMapping(value = "/migrate-local-chunks", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkerRunResponse migrateLocalChunksToBucket() {
        int migratedChunks = backgroundWorkerService.migrateLocalChunksToBucket();
        return new WorkerRunResponse("migrateLocalChunksToBucket", migratedChunks);
    }

    private static Instant parseReferenceTime(String referenceTime) {
        if (referenceTime == null || referenceTime.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(referenceTime);
        } catch (DateTimeParseException error) {
            throw new ValidationException(
                "referenceTime must be an ISO-8601 instant, got: " + referenceTime
            );
        }
    }
}
