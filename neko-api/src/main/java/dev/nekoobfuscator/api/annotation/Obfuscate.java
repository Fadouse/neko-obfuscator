package dev.nekoobfuscator.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Obfuscate {
    String[] passes() default {};
}
