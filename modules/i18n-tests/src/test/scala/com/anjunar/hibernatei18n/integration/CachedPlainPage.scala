package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** Verifies that the query-region decorator leaves ordinary entity caching intact. */
@Entity
@Table(name = "cached_plain_page")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
class CachedPlainPage:
  @Id var id: util.UUID = uninitialized
  var label: String = uninitialized
