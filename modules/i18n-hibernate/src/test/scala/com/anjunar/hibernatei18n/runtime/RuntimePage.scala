package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{AttributeConverter, Convert, Converter, Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.MappingException
import org.hibernate.StaleObjectStateException
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "development_page")
class RuntimePage:
  @Id var id: util.UUID = uninitialized
  @Translation var title: String = uninitialized
  @Translation @Convert(converter = classOf[RuntimeMarkdownConverter]) var content: RuntimeMarkdown = uninitialized
