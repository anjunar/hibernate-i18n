package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Convert, Entity, Id, Table}
import org.hibernate.Session
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import java.nio.file.Path
import munit.FunSuite
class RuntimeGetterI18nSuite extends FunSuite:
  test("runtime bootstrap preserves getter property types and editor access") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-getter-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeGetterPage].getName)
          .buildMetadata()
        assertEquals(
          metadata.getEntityBinding(classOf[RuntimeGetterPage].getName)
            .getTable.getColumn(new Column("title")),
          null
        )
        assert(metadata.getEntityBinding(
          TranslationMappingXml.translationEntityName(
            classOf[RuntimeGetterPage]
          )
        )
          .getTable.getColumn(new Column("displayTitle")) != null)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val translations = HibernateI18n.translations(factory, classOf[RuntimeGetterPage])
          val titleField = translations.field[String]("title")
          val contentField = translations.field[RuntimeMarkdown]("content")
          val id = util.UUID.randomUUID()
          def inLocale[A](locale: String)(body: Session => A): A =
            Using.resource(HibernateI18n.openSession(factory, locale)) { session =>
              val transaction = session.beginTransaction()
              try
                val result = body(session)
                transaction.commit()
                result
              catch
                case error: Throwable =>
                  if transaction.isActive then transaction.rollback()
                  throw error
            }
          inLocale("de") { session =>
            val page = new RuntimeGetterPage()
            page.setId(id)
            page.setTitle("Titel")
            page.setContent(RuntimeMarkdown("**Deutsch**"))
            session.persist(page)
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimeGetterPage], id)
            assertEquals(page.getTitle, "Titel")
            assertEquals(page.getContent, RuntimeMarkdown("**Deutsch**"))
            translations.setActive(session, page, "title", "Title")
            assertEquals(page.getTitle, "Title")
            translations.setActive(session, page, "content", null)
            assertEquals(page.getContent, null)
            translations.setActive(session, page, "content", RuntimeMarkdown("**Deutsch**"))
            intercept[IllegalArgumentException](translations.setActive(session, page, "id", util.UUID.randomUUID()))
            intercept[IllegalArgumentException](translations.setActive(session, page, "content", "Wrong type"))
            assertEquals(
              session.createQuery(
                "select p.title from RuntimeGetterPage p where p.id = :id",
                classOf[String]
              ).setParameter("id", id).getSingleResult,
              "Title"
            )
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimeGetterPage], id)
            assertEquals(page.getTitle, "Titel")
            assertEquals(translations.get(session, page, titleField, "en"), Some("Title"))
            translations.set(session, page, contentField, "en", RuntimeMarkdown("**English**"))
            assertEquals(page.getContent, RuntimeMarkdown("**Deutsch**"))
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimeGetterPage], id)
            assertEquals(page.getTitle, "Title")
            assertEquals(page.getContent, RuntimeMarkdown("**English**"))
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
