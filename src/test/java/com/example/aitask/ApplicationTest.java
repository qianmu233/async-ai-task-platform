package com.example.aitask;

import com.example.aitask.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "debug=false")
@ActiveProfiles("test")
class ApplicationTest {
    @Autowired
    private TaskService service;

    @Test
    void applicationWiresRealComponentsWithoutDatabase() {
        assertNotNull(service);
    }
}
