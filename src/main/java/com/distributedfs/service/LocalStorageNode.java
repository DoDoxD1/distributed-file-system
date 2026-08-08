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

    static final String CHUNK_DIRECTORY_NAME = "chunks";
    static final String CHUNK_FILE_SUFFIX = ".chunk";

    private final Path storageDirectory;

    public LocalStorageNode(String nodeId, String failureDomain, Path storageDirectory) {
        super(nodeId, failureDomain);
        this.storageDirectory = validateStorageDirectory(storageDirectory);

        try {
            Files.createDirectories(storageDirectory);
            Files.createDirectories(resolveChunkDirectory(storageDirectory));
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to initialize storage directories for node " + nodeId,
                error
            );
        }
    }

    @Override
    protected boolean hasChunkInternal(String chunkId) {
        return Files.exists(resolveChunkPath(storageDirectory, chunkId));
    }

    @Override
    protected void writeChunkInternal(String chunkId, byte[] payload) {
        try {
            Files.write(resolveChunkPath(storageDirectory, chunkId), payload);
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
            return Files.readAllBytes(resolveChunkPath(storageDirectory, chunkId));
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
            Files.delete(resolveChunkPath(storageDirectory, chunkId));
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to delete chunk " + chunkId + " from node " + nodeId(),
                error
            );
        }
    }

    @Override
    protected List<String> listChunksInternal() {
        try (Stream<Path> pathStream = Files.list(resolveChunkDirectory(storageDirectory))) {
            List<String> chunkIds = new ArrayList<>();
            pathStream
                .filter(LocalStorageNode::isChunkFile)
                .forEach(path -> chunkIds.add(chunkIdFromChunkFile(path)));
            chunkIds.sort(Comparator.naturalOrder());
            return chunkIds;
        } catch (IOException error) {
            throw new DistributedFsException(
                "Failed to list chunks on node " + nodeId(),
                error
            );
        }
    }

    static Path resolveChunkDirectory(Path storageDirectory) {
        return storageDirectory.resolve(CHUNK_DIRECTORY_NAME);
    }

    static Path resolveChunkPath(Path storageDirectory, String chunkId) {
        return resolveChunkDirectory(storageDirectory).resolve(validateChunkId(chunkId) + CHUNK_FILE_SUFFIX);
    }

    static boolean isChunkFile(Path path) {
        return path != null && path.getFileName() != null
            && path.getFileName().toString().endsWith(CHUNK_FILE_SUFFIX);
    }

    static String chunkIdFromChunkFile(Path path) {
        if (!isChunkFile(path)) {
            throw new ValidationException("path must reference a chunk file: " + path);
        }
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - CHUNK_FILE_SUFFIX.length());
    }

    private static Path validateStorageDirectory(Path storageDirectory) {
        if (storageDirectory == null) {
            throw new ValidationException("storageDirectory must be non-null");
        }
        return storageDirectory;
    }
}
