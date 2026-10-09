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
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@SchemaId("d31b4c30")
@Table(name = "Schedule#Event#Translation")
class RuntimeStackEventTranslation:
  @Id @SchemaId("d31b4c31") var id: UUID = uninitialized
  @SchemaId("d31b4c32") var locale: String = uninitialized
  @SchemaId("d31b4c33") var title: String = uninitialized

