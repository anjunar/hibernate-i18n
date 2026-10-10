package com.anjunar.hibernatei18n.runtime

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
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "development_long_text_page")
class RuntimeLongTextPage:
  @Id var id: util.UUID = uninitialized
  @Translation var title: String = uninitialized
  @Translation var description: String = uninitialized
