package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{AttributeConverter, Convert, Converter, Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.MappingException
import org.hibernate.StaleObjectStateException
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de")
@Table(name = "dev_extra_page")
class RuntimeSecondaryPage:
  @Id var id: UUID = uninitialized
  @Translation var title: String = uninitialized

