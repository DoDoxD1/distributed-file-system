package com.distributedfs.api;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:operational-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "distributed.fs.storage-backend=local",
    "distributed.fs.bootstrap-admin.email=admin@example.com",
    "distributed.fs.bootstrap-admin.password=password123",
    "distributed.fs.storage-root=target/operational-controller-test-storage"
})
class OperationalControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsHealthyMetadataConnection() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.database").value("UP"))
            .andExpect(jsonPath("$.checkedAt").isString());
    }

    @Test
    void versionReportsApplicationAndBuildVersion() throws Exception {
        mockMvc.perform(get("/api/v1/system/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.application").value("distributed-file-storage-system"))
            .andExpect(jsonPath("$.version", not(emptyOrNullString())));
    }
}
