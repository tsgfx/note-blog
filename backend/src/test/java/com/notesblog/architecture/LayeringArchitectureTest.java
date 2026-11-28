package com.notesblog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 已治理边界的防回归约束。
 *
 * <p>控制器可以编排 service，但不能知道 JPA repository 的存在；repository 的调用方
 * 只在 service 层。这样新增公开查询、账号查询或后台查询时，业务规则不会重新散回 HTTP 边缘。
 */
@AnalyzeClasses(packages = "com.notesblog", importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class LayeringArchitectureTest {

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repositories = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .as("controllers must not depend on repositories");

    @ArchTest
    static final ArchRule repositories_are_only_used_inside_persistence_or_service_layer = noClasses()
            .that().resideOutsideOfPackages("..service..", "..repository..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .as("repositories are owned by the service layer");

    @ArchTest
    static final ArchRule controllers_do_not_assemble_page_requests = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.domain.PageRequest")
            .as("pagination construction belongs to services");

    @ArchTest
    static final ArchRule controllers_do_not_assemble_sorts = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.domain.Sort")
            .as("sort construction belongs to services");
}
