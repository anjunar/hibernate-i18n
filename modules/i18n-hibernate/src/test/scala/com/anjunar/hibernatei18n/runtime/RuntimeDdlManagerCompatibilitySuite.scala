package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernateddl.core.{SchemaId, SqlType}
import com.anjunar.hibernateddl.executor.{ExecutionOptions, MigrationStatus}
import com.anjunar.hibernateddl.hibernate.HibernateSchemaSource
import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId as StableId}
import com.anjunar.hibernateddl.integration.HibernateSchemaMigration
import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeDdlManagerCompatibilitySuite extends munit.FunSuite:
  test("generated translation rows are part of the DDL manager model and migrate on both starts") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-ddl-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val dataSource = postgres.getPostgresDatabase
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "none")
        .applySetting("hibernate.default_schema", "public")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeDdlPage].getName)
          .buildMetadata()
        val desired = HibernateSchemaSource.read(metadata).toOption.get
        assertEquals(desired.tables.map(_.name.name.value).toSet,
          Set("development_ddl_page", "development_ddl_page_translation"))
        val translation = desired.tables.find(_.id == SchemaId("a31b4c20/translation")).get
        assertEquals(translation.columns.find(_.name.value == "title").map(_.dataType), Some(SqlType.Text))
        assertEquals(translation.columns.find(_.name.value == "title").map(_.id),
          Some(SchemaId("a31b4c20/translation/a31b4c22")))
        assertEquals(translation.foreignKeys.size, 1)
        assert(translation.foreignKeys.head.onDeleteCascade)
        HibernateSchemaMigration.migrate(metadata, dataSource)
        HibernateSchemaMigration.migrate(metadata, dataSource)
        Using.resource(dataSource.getConnection) { connection =>
          Using.resource(connection.createStatement()) { statement =>
            Using.resource(statement.executeQuery(
              "select count(*) from information_schema.tables " +
                "where table_schema = 'public' and table_name = 'development_ddl_page_translation'")) { rows =>
              assert(rows.next())
              assertEquals(rows.getInt(1), 1)
            }
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("tenant-scoped translation foreign key references the parent's unique key") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-tenant-ddl-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val dataSource = postgres.getPostgresDatabase
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "none")
        .applySetting("hibernate.default_schema", "public")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeTenantPage].getName)
          .buildMetadata()
        val desired = HibernateSchemaSource.read(metadata).toOption.get
        val parent = desired.tables.find(_.id == SchemaId("b31b4c20")).get
        val translation = desired.tables.find(_.id == SchemaId("b31b4c20/translation")).get
        val referenced = Vector(SchemaId("b31b4c20/b31b4c21"), SchemaId("b31b4c20/b31b4c22"))
        assert(parent.uniqueKeys.exists(_.columns == referenced))
        assertEquals(parent.columns.find(_.name.value == "tenant_id").map(_.dataType), Some(SqlType.Varchar(40)))
        assertEquals(translation.columns.find(_.name.value == "tenant_id").map(_.dataType), Some(SqlType.Varchar(40)))
        assertEquals(translation.foreignKeys.head.referencedColumns, referenced)
        assert(translation.foreignKeys.head.onDeleteCascade)
        HibernateSchemaMigration.migrate(metadata, dataSource)
        HibernateSchemaMigration.migrate(metadata, dataSource)
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("an existing Hibernate-created translation table with data is adopted without rewriting it") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-adopt-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val dataSource = postgres.getPostgresDatabase
      val oldRegistry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create")
        .applySetting("hibernate.default_schema", "public")
        .build()
      try
        val oldMetadata = new MetadataSources(oldRegistry)
          .addAnnotatedClassName(classOf[RuntimeDdlPage].getName)
          .addAnnotatedClassName(classOf[RuntimeTenantPage].getName)
          .buildMetadata()
        Using.resource(oldMetadata.buildSessionFactory()) { _ => () }
      finally StandardServiceRegistryBuilder.destroy(oldRegistry)
      val id = UUID.randomUUID()
      val tenantPageId = UUID.randomUUID()
      Using.resource(dataSource.getConnection) { connection =>
        Using.resource(connection.prepareStatement(
          "insert into public.development_ddl_page(id) values (?)")) { statement =>
          statement.setObject(1, id)
          assertEquals(statement.executeUpdate(), 1)
        }
        Using.resource(connection.prepareStatement(
          "insert into public.development_ddl_page_translation(page_id, locale, title, row_version) " +
            "values (?, 'de', 'Bestehend', 0)")) { statement =>
          statement.setObject(1, id)
          assertEquals(statement.executeUpdate(), 1)
        }
        Using.resource(connection.prepareStatement(
          "insert into public.development_tenant_page(id, tenant_id) values (?, 'tenant-a')")) { statement =>
          statement.setObject(1, tenantPageId)
          assertEquals(statement.executeUpdate(), 1)
        }
        Using.resource(connection.prepareStatement(
          "insert into public.development_tenant_page_translation(page_id, locale, tenant_id, title, row_version) " +
            "values (?, 'de', 'tenant-a', 'Mandant', 0)")) { statement =>
          statement.setObject(1, tenantPageId)
          assertEquals(statement.executeUpdate(), 1)
        }
        Using.resource(connection.createStatement()) { statement =>
          statement.execute("alter table public.development_ddl_page_translation " +
            "alter column title type varchar(255)")
          statement.execute("alter table public.development_tenant_page_translation " +
            "alter column title type varchar(255)")
        }
      }
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "none")
        .applySetting("hibernate.default_schema", "public")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeDdlPage].getName)
          .addAnnotatedClassName(classOf[RuntimeTenantPage].getName)
          .buildMetadata()
        assertEquals(TranslationSchemaUpgrade.widenTextColumns(metadata, dataSource), 2)
        val adopted = HibernateSchemaMigration.migrate(metadata, dataSource,
          ExecutionOptions(adoptExistingSchema = true))
        assertEquals(adopted.status, MigrationStatus.Adopted)
        val unchanged = HibernateSchemaMigration.migrate(metadata, dataSource)
        assertEquals(unchanged.status, MigrationStatus.AlreadyApplied)
        Using.resource(dataSource.getConnection) { connection =>
          Using.resource(connection.prepareStatement(
            "select title from public.development_ddl_page_translation where page_id = ? and locale = 'de'")) { statement =>
            statement.setObject(1, id)
            Using.resource(statement.executeQuery()) { rows =>
              assert(rows.next())
              assertEquals(rows.getString(1), "Bestehend")
            }
          }
          Using.resource(connection.prepareStatement(
            "select title from public.development_tenant_page_translation " +
              "where page_id = ? and locale = 'de' and tenant_id = 'tenant-a'")) { statement =>
            statement.setObject(1, tenantPageId)
            Using.resource(statement.executeQuery()) { rows =>
              assert(rows.next())
              assertEquals(rows.getString(1), "Mandant")
            }
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("quoted Stack tables and legacy translation entities are adopted together") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-stack-adopt-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val dataSource = postgres.getPostgresDatabase
      def configure(builder: StandardServiceRegistryBuilder, schemaAction: String) =
        builder
          .applySetting("hibernate.connection.datasource", dataSource)
          .applySetting("hibernate.hbm2ddl.auto", schemaAction)
          .applySetting("hibernate.default_schema", "public")
          .applySetting("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
          .applySetting("hibernate.cache.use_second_level_cache", "false")
          .applySetting("hibernate.cache.use_query_cache", "false")
          .build()
      def metadata(registry: org.hibernate.boot.registry.StandardServiceRegistry) =
        new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeStackOffering].getName)
          .addAnnotatedClassName(classOf[RuntimeStackOfferingTranslation].getName)
          .addAnnotatedClassName(classOf[RuntimeStackEvent].getName)
          .addAnnotatedClassName(classOf[RuntimeStackEventTranslation].getName)
          .buildMetadata()
      val oldRegistry = configure(HibernateI18n.registryBuilder(), "create")
      try Using.resource(metadata(oldRegistry).buildSessionFactory()) { _ => () }
      finally StandardServiceRegistryBuilder.destroy(oldRegistry)
      val registry = configure(HibernateI18n.registryBuilder(), "none")
      try
        val target = metadata(registry)
        val desired = HibernateSchemaSource.read(target).toOption.get
        assertEquals(desired.tables.size, 6)
        assert(desired.tables.exists(_.name.name.value == "Schedule#Event_translation"))
        assertEquals(HibernateSchemaMigration.migrate(target, dataSource,
          ExecutionOptions(adoptExistingSchema = true)).status, MigrationStatus.Adopted)
        assertEquals(HibernateSchemaMigration.migrate(target, dataSource).status,
          MigrationStatus.AlreadyApplied)
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
