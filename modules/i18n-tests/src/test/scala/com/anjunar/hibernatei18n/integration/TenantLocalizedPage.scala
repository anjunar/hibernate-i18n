package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** Shared-table tenant probe for the annotation-derived translation mapping. */
@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "tenant_page")
class TenantLocalizedPage:
  @Id var id: util.UUID = uninitialized
  @TenantId @Column(name = "tenant_id") var tenantId: String = uninitialized
  @Translation var title: String = uninitialized
