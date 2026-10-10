package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Cacheable, Entity, Id, Table}
import org.hibernate.annotations.{Cache, CacheConcurrencyStrategy}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@SchemaId("d31b4c20")
@Table(name = "Schedule#Event")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class RuntimeStackEvent extends RuntimeTenantBase:
  @Translation @SchemaId("d31b4c21") var title: String = uninitialized
