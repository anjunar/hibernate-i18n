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
@Table(name = "mixed_access_page")
class MixedAccessLocalizedPage:
  private var idValue: UUID = uninitialized
  @Translation var title: String = uninitialized

  @Id def getId: UUID = idValue
  def setId(value: UUID): Unit = idValue = value
