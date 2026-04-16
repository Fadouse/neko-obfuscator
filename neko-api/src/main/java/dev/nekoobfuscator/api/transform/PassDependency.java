package dev.nekoobfuscator.api.transform;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PassDependency {
    String[] before() default {};
    String[] after() default {};
}
