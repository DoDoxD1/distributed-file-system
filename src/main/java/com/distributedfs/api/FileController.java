package com.distributedfs.api;

import com.distributedfs.api.dto.DeleteFileResponse;
import com.distributedfs.api.dto.DownloadFileResponse;
import com.distributedfs.api.dto.CreateDirectUploadSessionRequest;
import com.distributedfs.api.dto.DirectUploadSessionResponse;
import com.distributedfs.api.dto.FileListingResponse;
import com.distributedfs.api.dto.FileManifestResponse;
import com.distributedfs.api.dto.UploadFileRequest;
import com.distributedfs.api.dto.UploadFileResponse;
import com.distributedfs.config.DistributedFsProperties;
import com.distributedfs.config.OpenApiConfiguration;
import com.distributedfs.error.PayloadTooLargeException;
import com.distributedfs.error.ValidationException;
import com.distributedfs.model.AuthenticatedUser;
import com.distributedfs.model.DirectUploadSession;
import com.distributedfs.model.FileListing;
import com.distributedfs.model.FileManifest;
import com.distributedfs.service.DirectTransferService;
import com.distributedfs.service.UserFileService;
import io.swagger.v3.oas.annotations.Operation;
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
    private final DirectTransferService directTransferService;
    private final DistributedFsProperties properties;

    public FileController(
        UserFileService userFileService,
        DirectTransferService directTransferService,
        DistributedFsProperties properties
    ) {
        this.userFileService = userFileService;
        this.directTransferService = directTransferService;
        this.properties = properties;
    }

    @Operation(
        summary = "Upload a file",
        description = "Stores a base64-encoded file payload for the authenticated user, creating a new immutable version."
    )
    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public UploadFileResponse uploadFile(
        @Valid @RequestBody UploadFileRequest request,
        HttpServletRequest httpRequest
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        validateDecodedPayloadSize(request.payloadBase64());
        byte[] payload = Base64.getDecoder().decode(request.payloadBase64());
        FileManifest manifest = userFileService.uploadFile(
            user,
            request.logicalPath(),
            payload,
            request.idempotencyKey()
        );
        return new UploadFileResponse(FileManifestResponse.fromManifest(manifest));
    }

    @Operation(
        summary = "Create a direct upload session",
        description = "Plans a Choice A direct-transfer upload for the authenticated user and returns session metadata for dedup-aware upload handling."
    )
    @PostMapping(value = "/direct/upload-sessions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DirectUploadSessionResponse createDirectUploadSession(
        @Valid @RequestBody CreateDirectUploadSessionRequest request,
        HttpServletRequest httpRequest
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        DirectUploadSession session = directTransferService.createUploadSession(
            user,
            request.logicalPath(),
            request.checksumSha256(),
            request.sizeBytes(),
            request.contentType(),
            request.idempotencyKey()
        );
        return DirectUploadSessionResponse.fromSession(session);
    }

    @Operation(
        summary = "Get direct upload session",
        description = "Returns the current direct-transfer upload session plan for the authenticated user."
    )
    @GetMapping(value = "/direct/upload-sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DirectUploadSessionResponse getDirectUploadSession(
        @PathVariable String sessionId,
        HttpServletRequest httpRequest
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        DirectUploadSession session = directTransferService.getUploadSession(user, sessionId);
        return DirectUploadSessionResponse.fromSession(session);
    }

    @Operation(
        summary = "Download file content",
        description = "Returns the requested file version as a base64-encoded payload for the authenticated user."
    )
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

    @Operation(
        summary = "Get file metadata",
        description = "Fetches the manifest for the latest or requested file version, with optional inclusion of deleted versions."
    )
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

    @Operation(
        summary = "Delete a file version",
        description = "Marks the latest or requested file version as deleted for the authenticated user and releases its chunk references."
    )
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

    @Operation(
        summary = "List files",
        description = "Lists active files in the authenticated user's namespace, optionally filtered by a logical-path prefix."
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FileListingResponse> listFiles(
        HttpServletRequest httpRequest,
        @RequestParam(value = "prefix", defaultValue = "") String prefix
    ) {
        AuthenticatedUser user = RequestUserContext.requireAuthenticatedUser(httpRequest);
        List<FileListing> listings = userFileService.listFiles(user, prefix);
        return listings.stream().map(FileListingResponse::fromListing).toList();
    }

    @Operation(
        summary = "List file versions",
        description = "Returns the active version history for a logical file path in the authenticated user's namespace."
    )
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

    private void validateDecodedPayloadSize(String payloadBase64) {
        if (payloadBase64 == null || payloadBase64.isBlank()) {
            throw new ValidationException("payloadBase64 must be non-empty");
        }
        String normalizedPayloadBase64 = payloadBase64.strip();
        int paddingLength = 0;
        if (normalizedPayloadBase64.endsWith("==")) {
            paddingLength = 2;
        } else if (normalizedPayloadBase64.endsWith("=")) {
            paddingLength = 1;
        }
        long estimatedDecodedSize = ((long) normalizedPayloadBase64.length() * 3 / 4)
            - paddingLength;
        if (estimatedDecodedSize < 0) {
            estimatedDecodedSize = 0;
        }
        long maxFileSizeBytes = properties.getMaxFileSizeBytes();
        if (estimatedDecodedSize > maxFileSizeBytes) {
            throw new PayloadTooLargeException(
                "Upload payload exceeds maximum allowed size: "
                    + estimatedDecodedSize + " > " + maxFileSizeBytes
            );
        }
    }
}
