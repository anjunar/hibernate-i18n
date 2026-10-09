package com.anjunar.hibernatei18n.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a property whose persistent value belongs to a locale-specific row. */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Translation {
    /**
     * Overrides the column name in the generated translation table.
     *
     * @return the column name, or empty to use the property name
     */
    String column() default "";
}
