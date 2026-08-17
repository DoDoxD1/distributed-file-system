package com.distributedfs.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:file-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "distributed.fs.storage-backend=local",
    "distributed.fs.bootstrap-admin.email=admin@example.com",
    "distributed.fs.bootstrap-admin.password=password123",
    "distributed.fs.storage-root=target/file-controller-test-storage",
    "distributed.fs.max-file-size-bytes=3",
    "distributed.fs.max-user-storage-bytes=20"
})
class FileControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadRejectsPayloadLargerThanConfiguredMaxBeforeDecode() throws Exception {
        String accessToken = registerAndExtractAccessToken();
        String oversizedPayloadBase64 = Base64.getEncoder().encodeToString("four".getBytes());

        mockMvc.perform(
            post("/api/v1/files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "logicalPath": "/docs/large.bin",
                      "payloadBase64": "%s"
                    }
                    """.formatted(oversizedPayloadBase64))
        )
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("payload_too_large"))
            .andExpect(jsonPath("$.message", containsString("4 > 3")));
    }

    @Test
    void directUploadSessionCreateAndReadReturnScopedSessionPlan() throws Exception {
        String accessToken = registerAndExtractAccessToken();

        MvcResult createSessionResult = mockMvc.perform(
            post("/api/v1/files/direct/upload-sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "logicalPath": "/docs/report.pdf",
                      "checksumSha256": "8f434346648f6b96df89dda901c5176b10a6d83961f9778f7f1449d84d35a32c",
                      "sizeBytes": 3,
                      "contentType": "application/pdf",
                      "idempotencyKey": "direct-request-1"
                    }
                    """)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logicalPath").value("/docs/report.pdf"))
            .andExpect(jsonPath("$.checksumSha256").value("8f434346648f6b96df89dda901c5176b10a6d83961f9778f7f1449d84d35a32c"))
            .andExpect(jsonPath("$.sizeBytes").value(3))
            .andExpect(jsonPath("$.status").value("AWAITING_UPLOAD"))
            .andExpect(jsonPath("$.uploadRequired").value(true))
            .andExpect(jsonPath("$.stagingObjectKey", containsString("/staging/")))
            .andReturn();

        String sessionId = extractJsonField(
            createSessionResult.getResponse().getContentAsString(),
            "sessionId"
        );

        mockMvc.perform(
            get("/api/v1/files/direct/upload-sessions/{sessionId}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId))
            .andExpect(jsonPath("$.logicalPath").value("/docs/report.pdf"))
            .andExpect(jsonPath("$.status").value("AWAITING_UPLOAD"));
    }

    private String registerAndExtractAccessToken() throws Exception {
        String emailAddress = "upload-test-" + UUID.randomUUID() + "@example.com";
        MvcResult registerResult = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "password123"
                    }
                    """.formatted(emailAddress))
        )
            .andExpect(status().isOk())
            .andReturn();

        return extractJsonField(registerResult.getResponse().getContentAsString(), "token");
    }

    private String extractJsonField(String json, String fieldName) {
        String needle = "\"" + fieldName + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("Missing JSON field: " + fieldName);
        }
        int valueStart = start + needle.length();
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) {
            throw new IllegalStateException("Unterminated JSON field: " + fieldName);
        }
        return json.substring(valueStart, valueEnd);
    }
}
