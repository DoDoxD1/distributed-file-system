package com.distributedfs.api;

import com.distributedfs.api.dto.DeleteFileResponse;
import com.distributedfs.api.dto.DownloadFileResponse;
import com.distributedfs.api.dto.FileListingResponse;
import com.distributedfs.api.dto.FileManifestResponse;
import com.distributedfs.api.dto.UploadFileRequest;
import com.distributedfs.api.dto.UploadFileResponse;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import com.distributedfs.service.GatewayService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for upload/download/delete/list/version workflows.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final GatewayService gatewayService;

    public FileController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public UploadFileResponse uploadFile(@Valid @RequestBody UploadFileRequest request) {
        byte[] payload = Base64.getDecoder().decode(request.payloadBase64());
        FileManifest manifest = gatewayService.uploadFile(
            request.logicalPath(),
            payload,
            request.idempotencyKey()
        );
        return new UploadFileResponse(FileManifestResponse.fromManifest(manifest));
    }

    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public DownloadFileResponse downloadFile(
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId
    ) {
        byte[] payload = gatewayService.downloadFile(logicalPath, versionId);
        String payloadBase64 = Base64.getEncoder().encodeToString(payload);
        return new DownloadFileResponse(logicalPath, versionId, payloadBase64);
    }

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileManifestResponse getManifest(
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId,
        @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted
    ) {
        FileManifest manifest = gatewayService.getManifest(
            logicalPath,
            versionId,
            includeDeleted
        );
        return FileManifestResponse.fromManifest(manifest);
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DeleteFileResponse deleteFile(
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId
    ) {
        FileManifest deleted = gatewayService.deleteFile(logicalPath, versionId);
        return new DeleteFileResponse(FileManifestResponse.fromManifest(deleted));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FileListingResponse> listFiles(
        @RequestParam(value = "prefix", defaultValue = "") String prefix
    ) {
        List<FileListing> listings = gatewayService.listFiles(prefix);
        return listings.stream().map(FileListingResponse::fromListing).toList();
    }

    @GetMapping(value = "/versions/{encodedPath}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FileManifestResponse> listVersions(@PathVariable String encodedPath) {
        String logicalPath = new String(
            Base64.getUrlDecoder().decode(encodedPath),
            StandardCharsets.UTF_8
        );
        return gatewayService.listVersions(logicalPath).stream()
            .map(FileManifestResponse::fromManifest)
            .toList();
    }
}
