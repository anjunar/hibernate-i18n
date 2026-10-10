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
@Table(name = "development_plain_cached_page")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class RuntimePlainCachedPage:
  @Id var id: util.UUID = uninitialized
  var title: String = uninitialized
