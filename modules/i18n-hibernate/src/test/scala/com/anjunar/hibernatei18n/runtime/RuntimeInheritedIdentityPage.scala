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

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "dev_inherited_identity")
class RuntimeInheritedIdentityPage extends RuntimeTenantBase:
  @Translation var title: String = uninitialized
