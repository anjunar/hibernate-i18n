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
@Localized(defaultLocale = "de")
@Table(name = "duplicate_column_page")
class DuplicateTranslationColumnPage:
  @Id var id: UUID = uninitialized
  @Translation(column = "display_title") var title: String = uninitialized
  @Translation(column = "display_title") var subtitle: String = uninitialized
