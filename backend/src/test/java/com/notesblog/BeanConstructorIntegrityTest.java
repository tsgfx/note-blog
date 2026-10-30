package com.notesblog;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

class BeanConstructorIntegrityTest {

    @Test
    @DisplayName("所有 Spring Bean 如果包含多个公共构造器，必须有 @Autowired 明确注入目标，防止运行时 No default constructor found 致命崩溃")
    void allBeansWithMultipleConstructorsMustHaveAutowired() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.notesblog");
        for (BeanDefinition beanDef : candidates) {
            Class<?> clazz = Class.forName(beanDef.getBeanClassName());
            Constructor<?>[] constructors = clazz.getConstructors();
            if (constructors.length > 1) {
                long autowiredCount = 0;
                for (Constructor<?> c : constructors) {
                    if (c.isAnnotationPresent(Autowired.class)) {
                        autowiredCount++;
                    }
                }
                assertThat(autowiredCount)
                        .withFailMessage("类 %s 拥有 %d 个构造器，但其中标记 @Autowired 的数量为 %d（Spring 无法推断，启动将直接抛 No default constructor 崩溃）",
                                clazz.getName(), constructors.length, autowiredCount)
                        .isEqualTo(1);
            }
        }
    }
}
