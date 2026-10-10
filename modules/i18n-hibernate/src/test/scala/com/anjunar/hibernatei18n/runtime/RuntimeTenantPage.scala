package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, Entity, Id, Table}
import org.hibernate.Session
import org.hibernate.annotations.TenantId
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.Using

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@SchemaId("b31b4c20")
@Table(name = "development_tenant_page")
class RuntimeTenantPage:
  @Id @SchemaId("b31b4c21") var id: util.UUID = uninitialized
  @TenantId @Column(name = "tenant_id", length = 40) @SchemaId("b31b4c22") var tenantId: String = uninitialized
  @Translation @SchemaId("b31b4c23") var title: String = uninitialized
