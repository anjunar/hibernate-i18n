package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

/** Physical naming probe for generated SQL formulas and query spaces. */
@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "namingPage")
class NamingLocalizedPage:
  @Id @Column(name = "documentId") var id: util.UUID = uninitialized
  @Translation var displayTitle: String = uninitialized
