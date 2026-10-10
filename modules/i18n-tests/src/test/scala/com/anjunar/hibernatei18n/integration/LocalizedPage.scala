package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "page")
class LocalizedPage:
  @Id var id: util.UUID = uninitialized
  var slug: String = uninitialized
  @Translation var title: String = uninitialized
  @Translation @Convert(converter = classOf[MarkdownConverter]) var content: Markdown = uninitialized
