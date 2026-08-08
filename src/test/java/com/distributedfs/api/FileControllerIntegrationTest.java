package com.distributedfs.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
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

    private String registerAndExtractAccessToken() throws Exception {
        MvcResult registerResult = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "upload-test@example.com",
                      "password": "password123"
                    }
                    """)
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
