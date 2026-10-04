package testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a public no-arg method as a test. The optional value is a human readable name. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
    String value() default "";

    /** Per-test timeout in milliseconds. */
    long timeoutMillis() default 10_000;
}
