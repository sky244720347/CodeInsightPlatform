package com.company.codeinsight.modules.system;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.vo.SystemSummaryVO;
import com.company.codeinsight.modules.system.service.SystemApplicationService;
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
public class SystemApplicationServiceTests {

    @Autowired
    private SystemApplicationService systemApplicationService;

    @Test
    public void testCrud() {
        SystemApplication system = new SystemApplication();
        system.setName("测试系统");
        system.setDescription("测试描述");
        system.setOwner("Tester");

        // Save
        boolean saved = systemApplicationService.save(system);
        Assertions.assertTrue(saved);
        Assertions.assertNotNull(system.getId());

        // Get
        SystemApplication fetched = systemApplicationService.getById(system.getId());
        Assertions.assertEquals("测试系统", fetched.getName());
        Assertions.assertEquals("Tester", fetched.getOwner());

        // Page list（仅按 name / owner 过滤）
        Page<SystemSummaryVO> page = systemApplicationService.listSystemsPage(1, 10, "测试", null);
        Assertions.assertTrue(page.getTotal() > 0);

        // Update
        fetched.setName("更新测试系统");
        systemApplicationService.updateById(fetched);
        SystemApplication updated = systemApplicationService.getById(system.getId());
        Assertions.assertEquals("更新测试系统", updated.getName());

        // Delete
        boolean removed = systemApplicationService.removeById(system.getId());
        Assertions.assertTrue(removed);
    }
}
