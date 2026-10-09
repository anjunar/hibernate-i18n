package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, DiscriminatorValue, Entity, Id, Inheritance, InheritanceType, MappedSuperclass, Table, Version}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.annotations.TenantId
import org.hibernate.MappingException

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using

@MappedSuperclass
abstract class RuntimeTenantBase extends RuntimeGlobalBase:
  @TenantId @Column(name = "tenant_id", nullable = false) @SchemaId("c31b4c03") var tenant: String = uninitialized
