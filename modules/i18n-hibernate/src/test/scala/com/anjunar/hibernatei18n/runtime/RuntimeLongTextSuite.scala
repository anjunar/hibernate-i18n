package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeLongTextSuite extends munit.FunSuite:
  test("explicit PostgreSQL upgrade widens old varchar translation columns before Hibernate update") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-text-upgrade-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      def build(mode: String, upgrade: Boolean = false): Unit =
        val registry = HibernateI18n.registryBuilder()
          .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
          .applySetting("hibernate.hbm2ddl.auto", mode)
          .build()
        try
          val metadata = new MetadataSources(registry)
            .addAnnotatedClassName(classOf[RuntimeLongTextPage].getName)
            .buildMetadata()
          if upgrade then
            assertEquals(TranslationSchemaUpgrade.widenTextColumns(metadata, postgres.getPostgresDatabase), 1)
            assertEquals(TranslationSchemaUpgrade.widenTextColumns(metadata, postgres.getPostgresDatabase), 0)
          Using.resource(metadata.buildSessionFactory())(_ => ())
        finally StandardServiceRegistryBuilder.destroy(registry)
      build("create")
      val id = UUID.randomUUID()
      Using.resource(postgres.getPostgresDatabase.getConnection) { connection =>
        Using.resource(connection.createStatement()) { statement =>
          statement.execute(
            "alter table development_long_text_page_translation alter column description type varchar(255)")
        }
        Using.resource(connection.prepareStatement(
          "insert into development_long_text_page (id) values (?)"
        )) { statement =>
          statement.setObject(1, id)
          statement.executeUpdate()
        }
        Using.resource(connection.prepareStatement(
          "insert into development_long_text_page_translation (page_id, locale, description, row_version) " +
            "values (?, ?, ?, 0)"
        )) { statement =>
          statement.setObject(1, id)
          statement.setString(2, "de")
          statement.setString(3, "Alter Inhalt")
          statement.executeUpdate()
        }
      }
      build("update", upgrade = true)
      Using.resource(postgres.getPostgresDatabase.getConnection) { connection =>
        Using.resource(connection.prepareStatement(
          "select data_type from information_schema.columns where table_name = ? and column_name = ?"
        )) { statement =>
          statement.setString(1, "development_long_text_page_translation")
          statement.setString(2, "description")
          Using.resource(statement.executeQuery()) { rows =>
            assert(rows.next())
            assertEquals(rows.getString(1), "text")
          }
        }
        Using.resource(connection.prepareStatement(
          "select description from development_long_text_page_translation where page_id = ? and locale = 'de'"
        )) { statement =>
          statement.setObject(1, id)
          Using.resource(statement.executeQuery()) { rows =>
            assert(rows.next())
            assertEquals(rows.getString(1), "Alter Inhalt")
          }
        }
      }
    }
  }

  test("translated text longer than varchar(255) persists in separate locale rows") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-long-text-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeLongTextPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          HibernateI18n.install(factory, classOf[RuntimeLongTextPage], _.id,
            Seq(
              TranslationField.string[RuntimeLongTextPage]("title", _.title),
              TranslationField.string[RuntimeLongTextPage]("description", _.description)
            ))
          val id = UUID.randomUUID()
          val german = "Ä" * 50000
          val english = "E" * 60000
          def inLocale(locale: String)(body: org.hibernate.Session => Unit): Unit =
            Using.resource(HibernateI18n.openSession(factory, locale)) { session =>
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
            val page = new RuntimeLongTextPage()
            page.id = id
            page.title = "Titel"
            page.description = german
            session.persist(page)
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimeLongTextPage], id)
            assertEquals(page.description, german)
            page.description = english
          }
          inLocale("de") { session =>
            assertEquals(session.find(classOf[RuntimeLongTextPage], id).description, german)
          }
          inLocale("en") { session =>
            assertEquals(session.find(classOf[RuntimeLongTextPage], id).description, english)
          }
          Using.resource(postgres.getPostgresDatabase.getConnection) { connection =>
            Using.resource(connection.prepareStatement(
              "select data_type from information_schema.columns where table_name = ? and column_name = ?"
            )) { statement =>
              statement.setString(1, "development_long_text_page_translation")
              statement.setString(2, "description")
              Using.resource(statement.executeQuery()) { rows =>
                assert(rows.next())
                assertEquals(rows.getString(1), "text")
              }
            }
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
