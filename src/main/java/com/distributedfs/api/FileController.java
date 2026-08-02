package com.distributedfs.api;

import com.distributedfs.api.dto.DeleteFileResponse;
import com.distributedfs.api.dto.DownloadFileResponse;
import com.distributedfs.api.dto.FileListingResponse;
import com.distributedfs.api.dto.FileManifestResponse;
import com.distributedfs.api.dto.UploadFileRequest;
import com.distributedfs.api.dto.UploadFileResponse;
import com.distributedfs.config.OpenApiConfiguration;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import com.distributedfs.service.UserFileService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
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
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
public class FileController {

    private final UserFileService userFileService;

    public FileController(UserFileService userFileService) {
        this.userFileService = userFileService;
    }

    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public UploadFileResponse uploadFile(
        @Valid @RequestBody UploadFileRequest request,
        HttpServletRequest httpRequest
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        byte[] payload = Base64.getDecoder().decode(request.payloadBase64());
        FileManifest manifest = userFileService.uploadFile(
            user,
            request.logicalPath(),
            payload,
            request.idempotencyKey()
        );
        return new UploadFileResponse(FileManifestResponse.fromManifest(manifest));
    }

    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public DownloadFileResponse downloadFile(
        HttpServletRequest httpRequest,
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        byte[] payload = userFileService.downloadFile(user, logicalPath, versionId);
        String payloadBase64 = Base64.getEncoder().encodeToString(payload);
        return new DownloadFileResponse(logicalPath, versionId, payloadBase64);
    }

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileManifestResponse getManifest(
        HttpServletRequest httpRequest,
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId,
        @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        FileManifest manifest = userFileService.getManifest(
            user,
            logicalPath,
            versionId,
            includeDeleted
        );
        return FileManifestResponse.fromManifest(manifest);
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DeleteFileResponse deleteFile(
        HttpServletRequest httpRequest,
        @RequestParam("path") String logicalPath,
        @RequestParam(value = "versionId", required = false) String versionId
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        FileManifest deleted = userFileService.deleteFile(user, logicalPath, versionId);
        return new DeleteFileResponse(FileManifestResponse.fromManifest(deleted));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FileListingResponse> listFiles(
        HttpServletRequest httpRequest,
        @RequestParam(value = "prefix", defaultValue = "") String prefix
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        List<FileListing> listings = userFileService.listFiles(user, prefix);
        return listings.stream().map(FileListingResponse::fromListing).toList();
    }

    @GetMapping(value = "/versions/{encodedPath}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FileManifestResponse> listVersions(
        HttpServletRequest httpRequest,
        @PathVariable String encodedPath
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        String logicalPath = new String(
            Base64.getUrlDecoder().decode(encodedPath),
            StandardCharsets.UTF_8
        );
        return userFileService.listVersions(user, logicalPath).stream()
            .map(FileManifestResponse::fromManifest)
            .toList();
    }
}
