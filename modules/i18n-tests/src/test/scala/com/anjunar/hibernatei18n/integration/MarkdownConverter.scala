package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

@Converter
class MarkdownConverter extends AttributeConverter[Markdown, String]:
  override def convertToDatabaseColumn(attribute: Markdown): String =
    if attribute == null then null else attribute.source

  override def convertToEntityAttribute(dbData: String): Markdown =
    if dbData == null then null else Markdown(dbData)
