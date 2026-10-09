package com.anjunar.hibernatei18n.integration

import java.util.function.UnaryOperator

/** Replaces a SQL formula marker with a fixed, validated locale for one Session. */
final class FixedLocaleSqlInspector(locale: String) extends UnaryOperator[String]:
  require(locale.matches("[A-Za-z0-9-]+"), s"Invalid locale tag: $locale")

  override def apply(sql: String): String =
    val language = locale.takeWhile(_ != '-')
    sql.replace("'__hibernate_i18n_locale__'", s"'$locale'")
      .replace("'__hibernate_i18n_language__'", s"'$language'")
