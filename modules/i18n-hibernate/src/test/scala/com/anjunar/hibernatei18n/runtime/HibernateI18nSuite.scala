package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{AttributeConverter, Convert, Converter, Entity, Id, Table}
import org.hibernate.boot.MetadataSources
import org.hibernate.MappingException
import org.hibernate.StaleObjectStateException
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using
import java.nio.file.Path
import munit.FunSuite
import org.hibernate.Hibernate
import org.hibernate.HibernateException
import org.hibernate.Session
import org.hibernate.mapping.Column
class HibernateI18nSuite extends FunSuite:
  test("a mismatched development field reader fails before persisting a translation") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-reader-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        Using.resource(new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeSecondaryPage].getName)
          .buildMetadata().buildSessionFactory()) { factory =>
          HibernateI18n.install(
            factory,
            classOf[RuntimeSecondaryPage],
            _.id,
            Seq(TranslationField.string[RuntimeSecondaryPage]("title", _ => "Wrong title"))
          )
          Using.resource(HibernateI18n.openSession(factory, "de")) { session =>
            val transaction = session.beginTransaction()
            try
              val page = new RuntimeSecondaryPage()
              page.id = util.UUID.randomUUID()
              page.title = "Actual title"
              session.persist(page)
              val error = intercept[RuntimeException](transaction.commit())
              val messages = Iterator.iterate[Throwable](error)(_.getCause)
                .takeWhile(_ != null).flatMap(cause => Option(cause.getMessage))
              assert(messages.exists(_.contains("reader for")))
            finally
              if transaction.isActive then transaction.rollback()
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("published runtime bootstrap loads and writes ordinary typed properties by locale") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val dataSource = postgres.getPostgresDatabase
      val normal = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource).build()
      try
        intercept[MappingException] {
          new MetadataSources(normal).addAnnotatedClassName(classOf[RuntimePage].getName).buildMetadata()
        }
      finally StandardServiceRegistryBuilder.destroy(normal)
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimePage].getName)
          .addAnnotatedClassName(classOf[RuntimeSecondaryPage].getName).buildMetadata()
        assertEquals(
          metadata.getEntityBinding(classOf[RuntimePage].getName)
            .getTable.getColumn(new Column("title")),
          null
        )
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val translations = HibernateI18n.translations(factory, classOf[RuntimePage])
          val titleField = translations.field[String]("title")
          val contentField = translations.field[RuntimeMarkdown]("content")
          assertEquals(translations.fieldNames.toSet, Set("title", "content"))
          assert(HibernateI18n.translations(factory, classOf[RuntimePage]) eq translations)
          intercept[IllegalArgumentException](translations.field[String]("id"))
          intercept[IllegalArgumentException](translations.field[String]("content"))
          intercept[IllegalArgumentException](translations.field[AnyRef]("title"))
          intercept[IllegalArgumentException](HibernateI18n.translations(factory, classOf[String]))
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
            val page = new RuntimePage()
            page.id = id
            page.title = "Hallo"
            page.content = RuntimeMarkdown("**Deutsch**")
            session.persist(page)
            val secondary = new RuntimeSecondaryPage()
            secondary.id = util.UUID.randomUUID()
            secondary.title = "Automatically registered"
            session.persist(secondary)
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
            page.title = "Hello"
            assertEquals(
              session.createQuery(
                "select p.title from RuntimePage p where p.id = :id",
                classOf[String]
              ).setParameter("id", id).getSingleResult,
              "Hello"
            )
          }
          inLocale("de") { session =>
            val page = session.getReference(classOf[RuntimePage], id)
            assert(!Hibernate.isInitialized(page))
            assertEquals(titleField.read(page), "Hallo")
            assertEquals(contentField.read(page), RuntimeMarkdown("**Deutsch**"))
          }
          inLocale("de") { session =>
            val page = session.getReference(classOf[RuntimePage], id)
            assert(!Hibernate.isInitialized(page))
            translations.setActive(session, page, "title", "Über Proxy")
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Über Proxy")
            page.title = "Hallo"
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            HibernateI18n.install(factory) // Repeated setup must preserve the field handles and listener state.
            assert(HibernateI18n.translations(factory, classOf[RuntimePage]) eq translations)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
            page.title = null
          }
          inLocale("en") { session =>
            assertEquals(session.find(classOf[RuntimePage], id).title, "Hallo")
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(translations.get(session, page, titleField, "en"), None)
            assertEquals(translations.get(session, page, contentField, "en"), None)
            intercept[IllegalArgumentException](translations.set[AnyRef](session, page, "title", "en", new Object))
            translations.set(session, page, "title", "en", "Hello editor")
            translations.set(session, page, "content", "en", RuntimeMarkdown("**English**"))
            assertEquals(translations.get[String](session, page, "title", "en"), Some("Hello editor"))
            assertEquals(
              translations.get[RuntimeMarkdown](session, page, "content", "en"),
              Some(RuntimeMarkdown("**English**"))
            )
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
            intercept[HibernateException] {
              translations.set(session, page, titleField, "de", "Nicht erlaubt")
            }
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Hello editor")
            assertEquals(page.content, RuntimeMarkdown("**English**"))
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            translations.set(session, page, "title", "en", null)
            assertEquals(translations.get(session, page, titleField, "en"), None)
            assertEquals(page.title, "Hallo")
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, RuntimeMarkdown("**English**"))
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            translations.set(session, page, contentField, "en", null)
            assertEquals(translations.get(session, page, contentField, "en"), None)
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
          }
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, RuntimeMarkdown("**Deutsch**"))
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            translations.set(session, page, titleField, "en", "English baseline")
            translations.set(session, page, titleField, "fr", "Bonjour baseline")
          }
          Using.resource(HibernateI18n.openSession(factory, "de")) { first =>
            Using.resource(HibernateI18n.openSession(factory, "de")) { second =>
              val firstTransaction = first.beginTransaction()
              val secondTransaction = second.beginTransaction()
              try
                val firstPage = first.find(classOf[RuntimePage], id)
                val secondPage = second.find(classOf[RuntimePage], id)
                assertEquals(
                  translations.get(first, firstPage, titleField, "en"),
                  Some("English baseline")
                )
                assertEquals(
                  translations.get(second, secondPage, titleField, "en"),
                  Some("English baseline")
                )
                translations.set(first, firstPage, titleField, "en", "Editor one")
                translations.set(second, secondPage, titleField, "en", "Editor two")
                firstTransaction.commit()
                val stale = intercept[RuntimeException](secondTransaction.commit())
                val causes = Iterator.iterate[Throwable](stale)(_.getCause).takeWhile(_ != null)
                assert(causes.exists(_.isInstanceOf[StaleObjectStateException]))
              finally
                if firstTransaction.isActive then firstTransaction.rollback()
                if secondTransaction.isActive then secondTransaction.rollback()
            }
          }
          Using.resource(HibernateI18n.openSession(factory, "de")) { first =>
            Using.resource(HibernateI18n.openSession(factory, "de")) { second =>
              val firstTransaction = first.beginTransaction()
              val secondTransaction = second.beginTransaction()
              try
                val firstPage = first.find(classOf[RuntimePage], id)
                val secondPage = second.find(classOf[RuntimePage], id)
                translations.set(first, firstPage, titleField, "en", "English independent")
                translations.set(second, secondPage, titleField, "fr", "Français indépendant")
                firstTransaction.commit()
                secondTransaction.commit()
                assertEquals(firstPage.title, "Hallo")
                assertEquals(secondPage.title, "Hallo")
              finally
                if firstTransaction.isActive then firstTransaction.rollback()
                if secondTransaction.isActive then secondTransaction.rollback()
            }
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(
              translations.get(session, page, titleField, "en"),
              Some("English independent")
            )
            assertEquals(
              translations.get(session, page, titleField, "fr"),
              Some("Français indépendant")
            )
            assertEquals(page.title, "Hallo")
          }
          Using.resource(HibernateI18n.openSession(factory, "de")) { editor =>
            Using.resource(HibernateI18n.openSession(factory, "en")) { domain =>
              val editorTransaction = editor.beginTransaction()
              val domainTransaction = domain.beginTransaction()
              try
                val editorPage = editor.find(classOf[RuntimePage], id)
                val domainPage = domain.find(classOf[RuntimePage], id)
                assertEquals(
                  translations.get(editor, editorPage, titleField, "en"),
                  Some("English independent")
                )
                translations.set(editor, editorPage, titleField, "en", "Editor wins")
                domainPage.title = "Domain loses"
                editorTransaction.commit()
                val stale = intercept[RuntimeException](domainTransaction.commit())
                val causes = Iterator.iterate[Throwable](stale)(_.getCause).takeWhile(_ != null)
                assert(causes.exists(_.isInstanceOf[StaleObjectStateException]))
              finally
                if editorTransaction.isActive then editorTransaction.rollback()
                if domainTransaction.isActive then domainTransaction.rollback()
            }
          }
          inLocale("en") { session =>
            assertEquals(session.find(classOf[RuntimePage], id).title, "Editor wins")
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            translations.set(session, page, titleField, "fr-CA", "Temporaire")
            assertEquals(
              translations.get(session, page, titleField, "fr-CA"),
              Some("Temporaire")
            )
            translations.set(session, page, titleField, "fr-CA", null)
            assertEquals(translations.get(session, page, titleField, "fr-CA"), None)
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], id)
            assertEquals(translations.get(session, page, titleField, "fr-CA"), None)
          }
          val secondaryId = util.UUID.randomUUID()
          inLocale("de") { session =>
            val secondary = new RuntimeSecondaryPage()
            secondary.id = secondaryId
            secondary.title = "Zweite Seite"
            session.persist(secondary)
          }
          inLocale("en") { session =>
            val secondary = session.find(classOf[RuntimeSecondaryPage], secondaryId)
            assertEquals(secondary.title, "Zweite Seite")
            secondary.title = "Second page"
          }
          inLocale("de") { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], secondaryId).title,
              "Zweite Seite"
            )
          }
          inLocale("en") { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], secondaryId).title,
              "Second page"
            )
          }
          inLocale("de") { session =>
            session.find(classOf[RuntimePage], id).title = "Hauptseite gemeinsam"
            session.find(classOf[RuntimeSecondaryPage], secondaryId).title = "Zweite gemeinsam"
          }
          inLocale("de") { session =>
            assertEquals(
              session.find(classOf[RuntimePage], id).title,
              "Hauptseite gemeinsam"
            )
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], secondaryId).title,
              "Zweite gemeinsam"
            )
          }
          val fallbackId = util.UUID.randomUUID()
          inLocale("en") { session =>
            val page = new RuntimePage()
            page.id = fallbackId
            page.title = "English fallback before edit"
            session.persist(page)
          }
          inLocale("de") { session =>
            val page = session.find(classOf[RuntimePage], fallbackId)
            assertEquals(page.title, "English fallback before edit")
            translations.set(session, page, titleField, "en", "English fallback after edit")
            assertEquals(page.title, "English fallback before edit")
            assertEquals(
              session.createQuery(
                "select p.title from RuntimePage p where p.id = :id",
                classOf[String]
              ).setParameter("id", fallbackId).getSingleResult,
              "English fallback after edit"
            )
            assert(session.find(classOf[RuntimePage], fallbackId) eq page)
            assertEquals(page.title, "English fallback before edit")
          }
          inLocale("de") { session =>
            assertEquals(
              session.find(classOf[RuntimePage], fallbackId).title,
              "English fallback after edit"
            )
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
