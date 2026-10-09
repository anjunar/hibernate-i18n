package com.anjunar.hibernatei18n.runtime

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, Entity, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeSchemaUpdateSuite extends munit.FunSuite:
  test("Hibernate update adds translation rows without replacing existing Stack tables") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-update-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val baselineRegistry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create")
        .applySetting("hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build()
      try
        val baselineMetadata = new MetadataSources(baselineRegistry)
          .addAnnotatedClassName(classOf[RuntimePreMigrationEvent].getName)
          .addAnnotatedClassName(classOf[RuntimeStackEventTranslation].getName)
          .buildMetadata()
        Using.resource(baselineMetadata.buildSessionFactory()) { baselineFactory =>
          val event = new RuntimePreMigrationEvent()
          event.legacyTitle = "Alter Titel"
          val legacy = new RuntimeStackEventTranslation()
          legacy.id = java.util.UUID.randomUUID()
          legacy.locale = "de"
          legacy.title = "Legacy-Termin"
          Using.resource(baselineFactory.withOptions().tenantIdentifier("tenant-a").openSession()) { session =>
            val tx = session.beginTransaction()
            session.persist(event)
            session.persist(legacy)
            tx.commit()
          }

          val registry = HibernateI18n.registryBuilder()
            .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
            .applySetting("hibernate.hbm2ddl.auto", "update")
            .applySetting("hibernate.physical_naming_strategy",
              "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .applySetting("hibernate.cache.use_query_cache", "false")
            .build()
          try
            val metadata = new MetadataSources(registry)
              .addAnnotatedClassName(classOf[RuntimeStackEvent].getName)
              .addAnnotatedClassName(classOf[RuntimeStackEventTranslation].getName)
              .buildMetadata()
            Using.resource(metadata.buildSessionFactory()) { factory =>
              HibernateI18n.install(factory, classOf[RuntimeStackEvent], _.id,
                Seq(TranslationField.string[RuntimeStackEvent]("title", _.title)))
              def inLocale(locale: String)(body: org.hibernate.Session => Unit): Unit =
                Using.resource(HibernateI18n.openSession(factory, locale, "tenant-a")) { session =>
                  val tx = session.beginTransaction()
                  try
                    body(session)
                    tx.commit()
                  catch
                    case error: Throwable =>
                      if tx.isActive then tx.rollback()
                      throw error
                }
              inLocale("de") { session =>
                val page = session.find(classOf[RuntimeStackEvent], event.id)
                assertEquals(page.title, null)
                page.title = "Termin"
                assertEquals(session.find(classOf[RuntimeStackEventTranslation], legacy.id).title,
                  "Legacy-Termin")
              }
              inLocale("en") { session =>
                val page = session.find(classOf[RuntimeStackEvent], event.id)
                assertEquals(page.title, "Termin")
                page.title = "Event"
              }
              inLocale("de") { session =>
                assertEquals(session.find(classOf[RuntimeStackEvent], event.id).title, "Termin")
              }
              Using.resource(baselineFactory.withOptions().tenantIdentifier("tenant-a").openSession()) { session =>
                assertEquals(session.find(classOf[RuntimePreMigrationEvent], event.id).legacyTitle,
                  "Alter Titel")
              }
            }
          finally StandardServiceRegistryBuilder.destroy(registry)
        }
      finally StandardServiceRegistryBuilder.destroy(baselineRegistry)
    }
  }
