package com.company.codeinsight.modules.ai;

import com.company.codeinsight.modules.ai.service.impl.AiSummaryServiceImpl;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@Transactional
public class AiSummaryServiceTest {

    @Autowired
    private AiSummaryService aiSummaryService;

    @Test
    public void testSensitiveInfoFilter() {
        AiSummaryServiceImpl impl = (AiSummaryServiceImpl) aiSummaryService;
        String input = "my password = 'secret123' and bearer 123456abcdef and jdbc:mysql://localhost:3306/db?user=root&password=mysqlpwd and http://192.168.1.100/api";
        String filtered = impl.filterSensitiveInfo(input);
        Assertions.assertTrue(filtered.contains("password=***") || filtered.contains("password = ***") || filtered.contains("pwd=***"));
        Assertions.assertTrue(filtered.contains("bearer ***"));
        Assertions.assertTrue(filtered.contains("http://***"));
    }
}
