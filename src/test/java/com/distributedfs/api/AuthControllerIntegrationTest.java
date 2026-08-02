package com.distributedfs.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "distributed.fs.storage-root=target/auth-controller-test-storage",
    "distributed.fs.access-token-ttl-seconds=900",
    "distributed.fs.refresh-token-ttl-seconds=86400",
    "distributed.fs.refresh-cookie-name=dfs_refresh_token",
    "distributed.fs.refresh-cookie-path=/api/v1/auth",
    "distributed.fs.refresh-cookie-secure=true",
    "distributed.fs.refresh-cookie-same-site=Strict"
})
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerAndRefreshRotateSecureRefreshCookie() throws Exception {
        MvcResult registerResult = mockMvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "cookie@example.com",
                      "password": "password123"
                    }
                    """)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isString())
            .andExpect(header().string("Set-Cookie", containsString("dfs_refresh_token=")))
            .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
            .andExpect(header().string("Set-Cookie", containsString("Secure")))
            .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")))
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=86400")))
            .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
            .andReturn();

        String registerAccessToken = extractJsonField(registerResult.getResponse().getContentAsString(), "token");
        Cookie refreshCookie = registerResult.getResponse().getCookie("dfs_refresh_token");

        MvcResult refreshResult = mockMvc.perform(
            post("/api/v1/auth/refresh")
                .cookie(refreshCookie)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isString())
            .andExpect(jsonPath("$.token", not(registerAccessToken)))
            .andExpect(header().string("Set-Cookie", containsString("dfs_refresh_token=")))
            .andReturn();

        Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie("dfs_refresh_token");

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .cookie(refreshCookie)
        )
            .andExpect(status().isUnauthorized());

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .cookie(rotatedRefreshCookie)
        )
            .andExpect(status().isOk());
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
