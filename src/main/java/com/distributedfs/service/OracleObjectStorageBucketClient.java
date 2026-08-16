package com.distributedfs.service;

import com.distributedfs.model.DirectUploadTarget;
import com.distributedfs.model.ObjectStorageObjectInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OracleObjectStorageBucketClient extends AutoCloseable {

    boolean objectExists(String objectName);

    void putObject(String objectName, byte[] payload);

    default void putObject(
        String objectName,
        byte[] payload,
        String contentType,
        Map<String, String> metadata
    ) {
        putObject(objectName, payload);
    }

    byte[] getObject(String objectName);

    void deleteObject(String objectName);

    List<String> listObjectNames(String prefix);

    DirectUploadTarget createUploadTarget(String objectName, Instant expiresAt);

    Optional<ObjectStorageObjectInfo> findObjectInfo(String objectName);

    void copyObject(String sourceObjectName, String destinationObjectName, Map<String, String> metadata);

    @Override
    void close();
}
