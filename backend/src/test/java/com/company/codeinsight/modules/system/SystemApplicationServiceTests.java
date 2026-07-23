package com.company.codeinsight.modules.system;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.exception.BusinessException;
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

        // Page list（按 name / component / owner 过滤）
        Page<SystemSummaryVO> page = systemApplicationService.listSystemsPage(1, 10, "测试", null, null, null);
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

    @Test
    public void testSoftDeleteSystem() {
        SystemApplication system = new SystemApplication();
        system.setName("软删测试系统-" + System.nanoTime());
        system.setOwner("Tester");
        systemApplicationService.save(system);

        Page<SystemSummaryVO> before = systemApplicationService.listSystemsPage(1, 10, system.getName(), null, null, null);
        Assertions.assertEquals(1, before.getTotal());

        systemApplicationService.softDeleteSystem(system.getId());

        Assertions.assertNull(systemApplicationService.getById(system.getId()));
        Page<SystemSummaryVO> after = systemApplicationService.listSystemsPage(1, 10, system.getName(), null, null, null);
        Assertions.assertEquals(0, after.getTotal());
    }

    @Test
    public void createSystem_rejectsDuplicateNameComponent() {
        String name = "dup-sys-" + System.nanoTime();
        SystemApplication first = new SystemApplication();
        first.setName(name);
        first.setComponent("billing");
        first.setOwner("Tester");
        systemApplicationService.createSystemDraft(first);
        Assertions.assertNotNull(first.getId());
        Assertions.assertEquals("billing", first.getComponent());

        SystemApplication dup = new SystemApplication();
        dup.setName(name);
        dup.setComponent("billing");
        dup.setOwner("Tester");
        BusinessException ex = Assertions.assertThrows(
                BusinessException.class,
                () -> systemApplicationService.createSystemDraft(dup));
        Assertions.assertTrue(ex.getMessage().contains("系统+组件已存在"));
    }

    @Test
    public void createSystem_allowsSameNameDifferentComponent() {
        String name = "multi-comp-" + System.nanoTime();
        SystemApplication a = new SystemApplication();
        a.setName(name);
        a.setComponent("api");
        a.setOwner("Tester");
        systemApplicationService.createSystemDraft(a);

        SystemApplication b = new SystemApplication();
        b.setName(name);
        b.setComponent("worker");
        b.setOwner("Tester");
        systemApplicationService.createSystemDraft(b);

        Assertions.assertNotEquals(a.getId(), b.getId());
    }

    @Test
    public void createSystem_blankComponentNormalizesToEmpty() {
        String name = "blank-comp-" + System.nanoTime();
        SystemApplication a = new SystemApplication();
        a.setName(name);
        a.setComponent("  ");
        a.setOwner("Tester");
        systemApplicationService.createSystemDraft(a);
        Assertions.assertEquals("", a.getComponent());

        SystemApplication dupEmpty = new SystemApplication();
        dupEmpty.setName(name);
        dupEmpty.setOwner("Tester");
        Assertions.assertThrows(
                BusinessException.class,
                () -> systemApplicationService.createSystemDraft(dupEmpty));
    }
}
