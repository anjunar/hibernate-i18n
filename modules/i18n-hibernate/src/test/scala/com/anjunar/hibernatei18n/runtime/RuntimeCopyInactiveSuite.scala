package com.anjunar.hibernatei18n.runtime

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.hibernate.Session
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util.UUID
import scala.util.Using

class RuntimeCopyInactiveSuite extends munit.FunSuite:
  test("copyInactive copies exact stored rows except the active locale") {
    val targetDirectory = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(targetDirectory)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(targetDirectory, "i18n-copy-inactive-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        Using.resource(new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimePage].getName)
          .buildMetadata().buildSessionFactory()) { factory =>
          val title = TranslationField.string[RuntimePage]("title", _.title)
          val content = TranslationField.converted[RuntimePage, RuntimeMarkdown](
            "content", _.content, _.source, RuntimeMarkdown.apply)
          val translations = HibernateI18n.install(factory, classOf[RuntimePage], _.id,
            Seq(title, content))
          val sourceId = UUID.randomUUID()
          val draftId = UUID.randomUUID()

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
            val source = new RuntimePage()
            source.id = sourceId
            source.title = "Quellseite"
            source.content = RuntimeMarkdown("Quellinhalt")
            session.persist(source)
            val draft = new RuntimePage()
            draft.id = draftId
            draft.title = "Entwurf"
            draft.content = RuntimeMarkdown("Entwurfsinhalt")
            session.persist(draft)
          }
          inLocale("de") { session =>
            val source = session.find(classOf[RuntimePage], sourceId)
            val draft = session.find(classOf[RuntimePage], draftId)
            translations.set(session, source, title, "en", "Old English")
            translations.set(session, source, content, "en", RuntimeMarkdown("English content"))
            translations.set(session, source, content, "fr", RuntimeMarkdown("Contenu français"))
            translations.set(session, draft, title, "en", "Stale draft title")
            translations.set(session, draft, content, "en", RuntimeMarkdown("Stale draft content"))
            translations.set(session, draft, title, "fr", "Ancien titre français")
            translations.set(session, draft, title, "es", "Borrador")
          }
          inLocale("de") { session =>
            val source = session.find(classOf[RuntimePage], sourceId)
            val draft = session.find(classOf[RuntimePage], draftId)
            translations.set(session, source, title, "en", "English source")
            translations.set(session, source, title, "it", "Pagina italiana")
            assertEquals(translations.copyInactive(session, source, draft), 3)
            assertEquals(draft.title, "Entwurf")
            assertEquals(draft.content, RuntimeMarkdown("Entwurfsinhalt"))
            assertEquals(translations.get(session, draft, title, "en"), Some("English source"))
            assertEquals(translations.get(session, draft, content, "en"),
              Some(RuntimeMarkdown("English content")))
            assertEquals(translations.get(session, draft, title, "fr"), None)
            assertEquals(translations.get(session, draft, content, "fr"),
              Some(RuntimeMarkdown("Contenu français")))
            assertEquals(translations.get(session, draft, title, "it"), Some("Pagina italiana"))
            assertEquals(translations.get(session, draft, title, "es"), Some("Borrador"))
            assertEquals(translations.copyInactive(session, source, draft), 3)
            intercept[IllegalArgumentException] {
              translations.copyInactive(session, source, source)
            }
          }
          inLocale("de") { session =>
            val source = session.find(classOf[RuntimePage], sourceId)
            val draft = session.find(classOf[RuntimePage], draftId)
            assertEquals(source.title, "Quellseite")
            assertEquals(draft.title, "Entwurf")
            assertEquals(translations.get(session, draft, title, "en"), Some("English source"))
            assertEquals(translations.get(session, draft, title, "fr"), None)
            assertEquals(translations.get(session, draft, title, "it"), Some("Pagina italiana"))
            assertEquals(translations.get(session, draft, title, "es"), Some("Borrador"))
          }
          inLocale("en") { session =>
            val draft = session.find(classOf[RuntimePage], draftId)
            assertEquals(draft.title, "English source")
            assertEquals(draft.content, RuntimeMarkdown("English content"))
          }
          val freshDraftId = UUID.randomUUID()
          inLocale("de") { session =>
            val source = session.find(classOf[RuntimePage], sourceId)
            val draft = new RuntimePage()
            draft.id = freshDraftId
            draft.title = "Neuer Entwurf"
            draft.content = RuntimeMarkdown("Neuer Inhalt")
            session.persist(draft)
            assertEquals(translations.copyInactive(session, source, draft), 3)
            assertEquals(draft.title, "Neuer Entwurf")
          }
          inLocale("en") { session =>
            val draft = session.find(classOf[RuntimePage], freshDraftId)
            assertEquals(draft.title, "English source")
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
