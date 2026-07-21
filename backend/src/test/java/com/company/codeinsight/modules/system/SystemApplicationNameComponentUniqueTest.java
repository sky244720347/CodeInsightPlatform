package com.company.codeinsight.modules.system;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.system.entity.SystemApplication;
import com.company.codeinsight.modules.system.mapper.SystemApplicationMapper;
import com.company.codeinsight.modules.system.service.impl.SystemApplicationServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * name + component 查重：不依赖 Spring / PG / Redis。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("系统 name+component 查重")
public class SystemApplicationNameComponentUniqueTest {

    @Mock
    private SystemApplicationMapper mapper;

    private SystemApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SystemApplicationServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    @DisplayName("normalizeComponent：空白归一为空串")
    void normalizeComponent() {
        Assertions.assertEquals("", SystemApplicationServiceImpl.normalizeComponent(null));
        Assertions.assertEquals("", SystemApplicationServiceImpl.normalizeComponent("  "));
        Assertions.assertEquals("billing", SystemApplicationServiceImpl.normalizeComponent(" billing "));
    }

    @Test
    @DisplayName("同 name+component 不允许插入")
    void createRejectsDuplicate() {
        when(mapper.selectCount(any())).thenReturn(1L);

        SystemApplication sys = new SystemApplication();
        sys.setName("order");
        sys.setComponent("billing");
        sys.setOwner("alice");

        BusinessException ex = Assertions.assertThrows(
                BusinessException.class,
                () -> service.createSystemDraft(sys));
        Assertions.assertTrue(ex.getMessage().contains("系统+组件已存在"));
        verify(mapper, never()).insert(any(SystemApplication.class));
    }

    @Test
    @DisplayName("无重复时允许插入，component 写入空串")
    void createAllowsUniqueAndNormalizesBlankComponent() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(SystemApplication.class))).thenAnswer(inv -> {
            SystemApplication s = inv.getArgument(0);
            s.setId(42L);
            return 1;
        });

        SystemApplication sys = new SystemApplication();
        sys.setName("order");
        sys.setComponent("  ");
        sys.setOwner("alice");

        SystemApplication created = service.createSystemDraft(sys);
        Assertions.assertEquals(42L, created.getId());
        Assertions.assertEquals("", created.getComponent());

        ArgumentCaptor<SystemApplication> captor = ArgumentCaptor.forClass(SystemApplication.class);
        verify(mapper).insert(captor.capture());
        Assertions.assertEquals("", captor.getValue().getComponent());
        Assertions.assertEquals("order", captor.getValue().getName());
    }

    @Test
    @DisplayName("更新撞车同 name+component 拒绝")
    void updateRejectsDuplicate() {
        SystemApplication existing = new SystemApplication();
        existing.setId(1L);
        existing.setName("order");
        existing.setComponent("api");
        existing.setOwner("alice");
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(1L);

        SystemApplication patch = new SystemApplication();
        patch.setName("order");
        patch.setComponent("billing");
        patch.setOwner("alice");

        BusinessException ex = Assertions.assertThrows(
                BusinessException.class,
                () -> service.updateSystemBasicInfo(1L, patch));
        Assertions.assertTrue(ex.getMessage().contains("系统+组件已存在"));
        verify(mapper, never()).updateById(any(SystemApplication.class));
    }
}
