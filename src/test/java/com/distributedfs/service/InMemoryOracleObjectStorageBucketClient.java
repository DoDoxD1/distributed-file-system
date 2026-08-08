package com.distributedfs.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class InMemoryOracleObjectStorageBucketClient implements OracleObjectStorageBucketClient {

    private final Map<String, byte[]> objects = new HashMap<>();

    @Override
    public boolean objectExists(String objectName) {
        return objects.containsKey(objectName);
    }

    @Override
    public void putObject(String objectName, byte[] payload) {
        objects.put(objectName, payload.clone());
    }

    @Override
    public byte[] getObject(String objectName) {
        return objects.get(objectName).clone();
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
}
