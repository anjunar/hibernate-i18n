package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Convert, Entity, Id, Table}
import org.hibernate.Session
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "development_getter_page")
class RuntimeGetterPage:
  private var idValue: util.UUID = uninitialized
  private var titleValue: String = uninitialized
  private var contentValue: RuntimeMarkdown = uninitialized

  @Id def getId: util.UUID = idValue
  def setId(value: util.UUID): Unit = idValue = value

  @Translation(column = "displayTitle") def getTitle: String = titleValue
  def setTitle(value: String): Unit = titleValue = value

  @Translation @Convert(converter = classOf[RuntimeMarkdownConverter])
  def getContent: RuntimeMarkdown = contentValue
  def setContent(value: RuntimeMarkdown): Unit = contentValue = value
