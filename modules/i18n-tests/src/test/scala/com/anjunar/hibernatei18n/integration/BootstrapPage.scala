package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util.UUID
import java.util.Objects
import scala.compiletime.uninitialized

/** Plain entity for verifying the build and database fixture. */
@Entity
@Table(name = "bootstrap_page")
class BootstrapPage:
  @Id var id: UUID = uninitialized
  var slug: String = uninitialized
