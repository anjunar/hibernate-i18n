package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util.UUID
import java.util.Objects
import scala.compiletime.uninitialized

/** JavaBean property-access probe: the normal getter still returns String. */
@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "property_page")
class PropertyLocalizedPage:
  private var idValue: UUID = uninitialized
  private var titleValue: String = uninitialized
  private var contentValue: Markdown = uninitialized

  @Id def getId: UUID = idValue
  def setId(value: UUID): Unit = idValue = value

  @Translation def getTitle: String = titleValue
  def setTitle(value: String): Unit = titleValue = value

  @Translation @Convert(converter = classOf[MarkdownConverter]) def getContent: Markdown = contentValue
  def setContent(value: Markdown): Unit = contentValue = value
