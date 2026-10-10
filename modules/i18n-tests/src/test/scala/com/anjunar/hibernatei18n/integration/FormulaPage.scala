package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** Probe entity: XML contributes formulas for the ordinary fields. */
@Entity
@Table(name = "formula_page")
class FormulaPage:
  @Id var id: util.UUID = uninitialized
  var slug: String = uninitialized
  var title: String = uninitialized
  @Convert(converter = classOf[MarkdownConverter]) var content: Markdown = uninitialized
