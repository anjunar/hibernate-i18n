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
class RuntimeCachedChild extends RuntimeCachedRoot:
  @Translation var title: String = uninitialized
