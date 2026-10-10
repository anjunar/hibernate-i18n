package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Cacheable, Entity, Id, Inheritance, InheritanceType, Table}
import org.hibernate.MappingException
import org.hibernate.annotations.{Cache, CacheConcurrencyStrategy}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de")
@Table(name = "development_cached_page")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class RuntimeCachedPage:
  @Id var id: util.UUID = uninitialized
  @Translation var title: String = uninitialized
