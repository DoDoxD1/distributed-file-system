package com.distributedfs.service;

import com.distributedfs.model.DirectUploadTarget;
import com.distributedfs.model.ObjectStorageObjectInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InMemoryOracleObjectStorageBucketClient implements OracleObjectStorageBucketClient {

    private final Map<String, StoredObject> objects = new HashMap<>();

    @Override
    public boolean objectExists(String objectName) {
        return objects.containsKey(objectName);
    }

    @Override
    public void putObject(String objectName, byte[] payload) {
        putObject(objectName, payload, null, Map.of());
    }

    @Override
    public void putObject(
        String objectName,
        byte[] payload,
        String contentType,
        Map<String, String> metadata
    ) {
        objects.put(
            objectName,
            new StoredObject(
                payload.clone(),
                contentType,
                metadata == null ? Map.of() : Map.copyOf(metadata)
            )
        );
    }

    @Override
    public byte[] getObject(String objectName) {
        return objects.get(objectName).payload().clone();
    }

    @Override
    public void deleteObject(String objectName) {
        objects.remove(objectName);
    }

    @Override
    public List<String> listObjectNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (String objectName : objects.keySet()) {
            if (objectName.startsWith(prefix)) {
                names.add(objectName);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public DirectUploadTarget createUploadTarget(String objectName, Instant expiresAt) {
        return new DirectUploadTarget(
            "https://example.invalid/upload/" + objectName,
            "PUT",
            Map.of()
        );
    }

    @Override
    public Optional<ObjectStorageObjectInfo> findObjectInfo(String objectName) {
        StoredObject storedObject = objects.get(objectName);
        if (storedObject == null) {
            return Optional.empty();
        }
        return Optional.of(
            new ObjectStorageObjectInfo(
                storedObject.payload().length,
                storedObject.contentType(),
                null,
                storedObject.metadata()
            )
        );
    }

    @Override
    public void copyObject(
        String sourceObjectName,
        String destinationObjectName,
        Map<String, String> metadata
    ) {
        StoredObject sourceObject = objects.get(sourceObjectName);
        if (sourceObject == null) {
            throw new IllegalStateException("Missing source object: " + sourceObjectName);
        }
        objects.put(
            destinationObjectName,
            new StoredObject(
                sourceObject.payload().clone(),
                sourceObject.contentType(),
                metadata == null ? sourceObject.metadata() : Map.copyOf(metadata)
            )
        );
    }

    @Override
    public void close() {
    }

    private record StoredObject(
        byte[] payload,
        String contentType,
        Map<String, String> metadata
    ) {
    }
}
