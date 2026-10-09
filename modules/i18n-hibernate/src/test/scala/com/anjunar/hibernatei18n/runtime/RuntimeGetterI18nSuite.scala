package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Convert, Entity, Id, Table}
import org.hibernate.Session
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeGetterI18nSuite extends munit.FunSuite:
  test("runtime bootstrap preserves getter property types and editor access") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
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
        assertEquals(metadata.getEntityBinding(classOf[RuntimeGetterPage].getName)
          .getTable.getColumn(new Column("title")), null)
        assert(metadata.getEntityBinding(
          com.anjunar.hibernatei18n.boot.TranslationMappingXml.translationEntityName(
            classOf[RuntimeGetterPage]))
          .getTable.getColumn(new Column("displayTitle")) != null)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val titleField = TranslationField.string[RuntimeGetterPage]("title", _.getTitle)
          val contentField = TranslationField.converted[RuntimeGetterPage, RuntimeMarkdown](
            "content", _.getContent, _.source, RuntimeMarkdown.apply)
          val translations = HibernateI18n.install(factory, classOf[RuntimeGetterPage],
            _.getId, Seq(titleField, contentField))
          val id = UUID.randomUUID()
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
            page.setTitle("Title")
            assertEquals(session.createQuery(
              "select p.title from RuntimeGetterPage p where p.id = :id", classOf[String]
            ).setParameter("id", id).getSingleResult, "Title")
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
