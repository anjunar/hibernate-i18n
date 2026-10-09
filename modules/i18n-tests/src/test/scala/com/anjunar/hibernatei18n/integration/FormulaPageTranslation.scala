package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util.UUID
import java.util.Objects
import scala.compiletime.uninitialized

@Entity
@Table(name = "formula_page_translation")
class FormulaPageTranslation:
  @EmbeddedId var id: FormulaTranslationId = uninitialized
  @MapsId("pageId")
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "page_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  var page: FormulaPage = uninitialized
  var title: String = uninitialized
  @Convert(converter = classOf[MarkdownConverter]) var content: Markdown = uninitialized
