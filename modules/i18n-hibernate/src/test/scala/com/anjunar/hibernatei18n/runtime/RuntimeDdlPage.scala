package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernateddl.core.{SchemaId, SqlType}
import com.anjunar.hibernateddl.executor.{ExecutionOptions, MigrationStatus}
import com.anjunar.hibernateddl.hibernate.HibernateSchemaSource
import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId as StableId}
import com.anjunar.hibernateddl.integration.HibernateSchemaMigration
import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de")
@StableId("a31b4c20")
@Table(name = "development_ddl_page")
class RuntimeDdlPage:
  @Id @StableId("a31b4c21") var id: util.UUID = uninitialized

  @Translation @StableId("a31b4c22") var title: String = uninitialized
