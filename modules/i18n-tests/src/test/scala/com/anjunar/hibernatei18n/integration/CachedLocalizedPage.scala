package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** Cache probe: deliberately unsafe with a localized formula in the entity state. */
@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "cached_page")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class CachedLocalizedPage:
  @Id var id: util.UUID = uninitialized
  @Translation var title: String = uninitialized
