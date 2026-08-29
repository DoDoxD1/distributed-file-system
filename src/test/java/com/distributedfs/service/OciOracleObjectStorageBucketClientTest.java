package com.distributedfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.distributedfs.model.ObjectStorageObjectInfo;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.CopyObjectRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OciOracleObjectStorageBucketClientTest {

    @Test
    void copyObjectIncludesDestinationRegionInCopyRequest() {
        ObjectStorageClient client = mock(ObjectStorageClient.class);
        OciOracleObjectStorageBucketClient bucketClient = new OciOracleObjectStorageBucketClient(
            client,
            "namespace",
            "bucket",
            "https://objectstorage.ap-mumbai-1.oraclecloud.com",
            "ap-mumbai-1",
            0,
            1L
        );

        bucketClient.copyObject("staging/object", "canonical/object", null);

        ArgumentCaptor<CopyObjectRequest> requestCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(requestCaptor.capture());
        CopyObjectRequest request = requestCaptor.getValue();

        assertEquals("namespace", request.getNamespaceName());
        assertEquals("bucket", request.getBucketName());
        assertEquals("staging/object", request.getCopyObjectDetails().getSourceObjectName());
        assertEquals("canonical/object", request.getCopyObjectDetails().getDestinationObjectName());
        assertEquals("ap-mumbai-1", request.getCopyObjectDetails().getDestinationRegion());
        assertNull(request.getCopyObjectDetails().getDestinationObjectMetadata());
    }

    @Test
    void copyObjectFallsBackToReadAndWriteWhenServiceCopyPermissionsAreInsufficient() {
        ObjectStorageClient client = mock(ObjectStorageClient.class);
        OciOracleObjectStorageBucketClient bucketClient = spy(new OciOracleObjectStorageBucketClient(
            client,
            "namespace",
            "bucket",
            "https://objectstorage.ap-mumbai-1.oraclecloud.com",
            "ap-mumbai-1",
            0,
            1L
        ));
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        Map<String, String> sourceMetadata = Map.of("sha256", "checksum");
        BmcException error = new BmcException(
            400,
            "InsufficientServicePermissions",
            "opc-request-id",
            "Permissions granted to the object storage service principal are insufficient."
        );

        doThrow(error).when(client).copyObject(org.mockito.ArgumentMatchers.any(CopyObjectRequest.class));
        doReturn(Optional.of(new ObjectStorageObjectInfo(7L, "application/pdf", null, sourceMetadata)))
            .when(bucketClient)
            .findObjectInfo("staging/object");
        doReturn(payload).when(bucketClient).getObject("staging/object");
        doNothing().when(bucketClient)
            .putObject(eq("canonical/object"), aryEq(payload), eq("application/pdf"), eq(sourceMetadata));

        bucketClient.copyObject("staging/object", "canonical/object", null);

        verify(client).copyObject(org.mockito.ArgumentMatchers.any(CopyObjectRequest.class));
        verify(bucketClient).findObjectInfo("staging/object");
        verify(bucketClient).getObject("staging/object");
        verify(bucketClient).putObject(
            eq("canonical/object"),
            aryEq(payload),
            eq("application/pdf"),
            eq(sourceMetadata)
        );
    }
}
