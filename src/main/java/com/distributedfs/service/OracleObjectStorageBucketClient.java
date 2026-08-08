package com.distributedfs.service;

import java.util.List;

public interface OracleObjectStorageBucketClient extends AutoCloseable {

    boolean objectExists(String objectName);

    void putObject(String objectName, byte[] payload);

    byte[] getObject(String objectName);

    void deleteObject(String objectName);

    List<String> listObjectNames(String prefix);

    @Override
    default void close() {
    }
}
