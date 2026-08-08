package com.distributedfs.service;

import com.distributedfs.error.DistributedFsException;
import com.distributedfs.error.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class LocalStorageNode extends StorageNode {

    private final Path storageDirectory;

    public LocalStorageNode(String nodeId, String failureDomain, Path storageDirectory) {
        super(nodeId, failureDomain);
        this.storageDirectory = validateStorageDirectory(storageDirectory);

        try {
            Files.createDirectories(storageDirectory);
            Files.createDirectories(chunkDirectory());
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to initialize storage directories for node " + nodeId,
                error
            );
        }
    }

    @Override
    protected boolean hasChunkInternal(String chunkId) {
        return Files.exists(chunkPath(chunkId));
    }

    @Override
    protected void writeChunkInternal(String chunkId, byte[] payload) {
        try {
            Files.write(chunkPath(chunkId), payload);
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to persist chunk " + chunkId + " on node " + nodeId(),
                error
            );
        }
    }

    @Override
    protected byte[] readChunkInternal(String chunkId) {
        try {
            return Files.readAllBytes(chunkPath(chunkId));
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to read chunk " + chunkId + " from node " + nodeId(),
                error
            );
        }
    }

    @Override
    protected void deleteChunkInternal(String chunkId) {
        try {
            Files.delete(chunkPath(chunkId));
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to delete chunk " + chunkId + " from node " + nodeId(),
                error
            );
        }
    }

    @Override
    protected List<String> listChunksInternal() {
        try (Stream<Path> pathStream = Files.list(chunkDirectory())) {
            List<String> chunkIds = new ArrayList<>();
            pathStream
                .filter(path -> path.getFileName().toString().endsWith(".chunk"))
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    chunkIds.add(fileName.substring(0, fileName.length() - 6));
                });
            chunkIds.sort(Comparator.naturalOrder());
            return chunkIds;
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to list chunks on node " + nodeId(),
                error
            );
        }
    }

    private Path chunkDirectory() {
        return storageDirectory.resolve("chunks");
    }

    private Path chunkPath(String chunkId) {
        return chunkDirectory().resolve(chunkId + ".chunk");
    }

    private static Path validateStorageDirectory(Path storageDirectory) {
        if (storageDirectory == null) {
            throw new ValidationException("storageDirectory must be non-null");
        }
        return storageDirectory;
    }
}
