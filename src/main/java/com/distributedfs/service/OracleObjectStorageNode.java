package com.distributedfs.service;

import com.distributedfs.error.ValidationException;
import java.util.Comparator;
import java.util.List;

public class OracleObjectStorageNode extends StorageNode {

    private static final String CHUNK_SUFFIX = ".chunk";
    private final OracleObjectStorageBucketClient bucketClient;
    private final String objectPrefix;

    public OracleObjectStorageNode(
        String nodeId,
        String failureDomain,
        OracleObjectStorageBucketClient bucketClient,
        String objectPrefix
    ) {
        super(nodeId, failureDomain);
        if (bucketClient == null) {
            throw new ValidationException("bucketClient must be non-null");
        }
        this.bucketClient = bucketClient;
        this.objectPrefix = normalizeObjectPrefix(objectPrefix);
    }

    @Override
    protected boolean hasChunkInternal(String chunkId) {
        return bucketClient.objectExists(objectName(chunkId));
    }

    @Override
    protected void writeChunkInternal(String chunkId, byte[] payload) {
        bucketClient.putObject(objectName(chunkId), payload);
    }

    @Override
    protected byte[] readChunkInternal(String chunkId) {
        return bucketClient.getObject(objectName(chunkId));
    }

    @Override
    protected void deleteChunkInternal(String chunkId) {
        bucketClient.deleteObject(objectName(chunkId));
    }

    @Override
    protected List<String> listChunksInternal() {
        return bucketClient.listObjectNames(nodeChunkPrefix()).stream()
            .filter(objectName -> objectName.endsWith(CHUNK_SUFFIX))
            .map(objectName -> objectName.substring(nodeChunkPrefix().length()))
            .map(fileName -> fileName.substring(0, fileName.length() - CHUNK_SUFFIX.length()))
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private String objectName(String chunkId) {
        return nodeChunkPrefix() + chunkId + CHUNK_SUFFIX;
    }

    private String nodeChunkPrefix() {
        String prefix = objectPrefix.isEmpty() ? "" : objectPrefix + "/";
        return prefix + "nodes/" + nodeId() + "/chunks/";
    }

    private static String normalizeObjectPrefix(String objectPrefix) {
        if (objectPrefix == null) {
            return "";
        }
        String normalized = objectPrefix.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
