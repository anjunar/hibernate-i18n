package com.anjunar.hibernatei18n.runtime

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, Entity, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import scala.compiletime.uninitialized
import scala.util.Using

@Entity
@Table(name = "Schedule#Event")
class RuntimePreMigrationEvent extends RuntimeTenantBase:
  @Column(name = "legacy_title") var legacyTitle: String = uninitialized

