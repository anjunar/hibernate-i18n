package com.anjunar.hibernatei18n.boot

import org.hibernate.MappingException
import org.hibernate.mapping.PersistentClass

/** Fail-closed cache constraints for formula-backed translated entity state. */
private[hibernatei18n] object TranslationCachePolicy:
  def validate(parent: PersistentClass, queryCacheEnabled: Boolean, localeAwareQueryRegion: Boolean): Unit =
    if queryCacheEnabled && !localeAwareQueryRegion then
      throw new MappingException("Localized formula mapping requires a locale-aware query cache region")
    if parent.isCached then
      throw new MappingException(s"Localized entity ${parent.getClassName} must not use second-level caching")
