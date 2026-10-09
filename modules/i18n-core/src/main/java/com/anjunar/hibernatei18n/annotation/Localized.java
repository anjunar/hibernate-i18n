package com.anjunar.hibernatei18n.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an entity that owns per-locale persistent translations. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Localized {
    /**
     * Defines the final locale consulted when the active locale has no value.
     *
     * @return the default locale, or empty if unset
     */
    String defaultLocale() default "";

    /**
     * Defines the preferred fallback locale.
     *
     * @return the fallback locale, or empty if unset
     */
    String fallbackLocale() default "";
}
