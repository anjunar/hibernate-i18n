package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** An ordinary @Column binds a parent column and must be rejected by the overlay. */
@Entity
@Localized(defaultLocale = "de")
@Table(name = "column_translation_page")
class ColumnAnnotatedLocalizedPage:
  @Id var id: util.UUID = uninitialized
  @Translation @Column(name = "display_title") var title: String = uninitialized
