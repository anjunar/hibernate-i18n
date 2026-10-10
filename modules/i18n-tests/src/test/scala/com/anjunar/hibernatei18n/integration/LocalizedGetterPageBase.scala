package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{AttributeConverter, Column, Convert, Converter, Embeddable, EmbeddedId, Entity, FetchType, Id, JoinColumn, ManyToOne, MappedSuperclass, MapsId, Table}
import org.hibernate.annotations.{OnDelete, OnDeleteAction, TenantId}
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy

import java.util
import scala.compiletime.uninitialized

@MappedSuperclass
abstract class LocalizedGetterPageBase:
  private var idValue: util.UUID = uninitialized
  private var titleValue: String = uninitialized

  @Id def getId: util.UUID = idValue
  def setId(value: util.UUID): Unit = idValue = value

  @Translation def getTitle: String = titleValue
  def setTitle(value: String): Unit = titleValue = value
