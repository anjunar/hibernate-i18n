package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util.UUID
import java.util.Objects
import scala.compiletime.uninitialized

/** Separate persistent identity for one page and one content locale. */
@Embeddable
class FormulaTranslationId extends Serializable:
  @Column(name = "page_id") var pageId: UUID = uninitialized
  var locale: String = uninitialized

  override def equals(other: Any): Boolean = other match
    case that: FormulaTranslationId => pageId == that.pageId && locale == that.locale
    case _ => false

  override def hashCode(): Int = Objects.hash(pageId, locale)
