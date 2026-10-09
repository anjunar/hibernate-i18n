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
final class TranslationField[P, V] private (
  val name: String,
  val read: P => V,
  val encode: V => Object,
  val decode: Object => V
):
  private[runtime] def readAny(page: P): Any = read(page)
  private[runtime] def encodeAny(value: Any): Object =
    encode(value.asInstanceOf[V])

object TranslationField:
  def string[P](name: String, read: P => String): TranslationField[P, String] =
    new TranslationField[P, String](name, read, identity, _.asInstanceOf[String])

  def converted[P, V](
    name: String,
    read: P => V,
    toDatabase: V => String,
    fromDatabase: String => V
  ): TranslationField[P, V] =
    new TranslationField[P, V](name, read,
      value => if value == null then null else toDatabase(value),
      value => if value == null then null.asInstanceOf[V]
        else fromDatabase(value.asInstanceOf[String]))

