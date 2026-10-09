package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{LocalizedEntityMembers, TranslationMappingXml, LocalizedBootstrapGuard}
import com.anjunar.hibernatei18n.runtime.SessionContentLocale
import org.hibernate.{HibernateException, Session, SessionFactory}
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.{AdditionalMappingContributor, MetadataSourcesContributor}
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType

import java.util.UUID
import java.util.WeakHashMap
import java.util.function.UnaryOperator
import scala.jdk.CollectionConverters.*
private final class LocaleInspector(locale: String) extends UnaryOperator[String]:
  require(locale.matches("[A-Za-z0-9-]+"), s"Invalid locale tag: $locale")

  override def apply(sql: String): String =
    val language = locale.takeWhile(_ != '-')
    sql.replace("'__hibernate_i18n_locale__'", s"'$locale'")
      .replace("'__hibernate_i18n_language__'", s"'$language'")
