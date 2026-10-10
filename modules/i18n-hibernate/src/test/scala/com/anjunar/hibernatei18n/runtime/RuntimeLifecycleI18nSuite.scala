package com.anjunar.hibernatei18n.runtime

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.hibernate.Session
import org.hibernate.HibernateException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.util.Using
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import java.nio.file.Path
import munit.FunSuite

class RuntimeLifecycleI18nSuite extends FunSuite:
  test("refresh, clear and evict preserve locale state and reject dirty row refresh") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-lifecycle-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        Using.resource(new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeSecondaryPage].getName)
          .buildMetadata().buildSessionFactory()) { factory =>
          HibernateI18n.install(
            factory,
            classOf[RuntimeSecondaryPage],
            _.id,
            Seq(TranslationField.string[RuntimeSecondaryPage]("title", _.title))
          )
          val id = util.UUID.randomUUID()
          def inGerman[A](body: Session => A): A =
            Using.resource(HibernateI18n.openSession(factory, "de")) { session =>
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
          inGerman { session =>
            val page = new RuntimeSecondaryPage()
            page.id = id
            page.title = "Initial"
            session.persist(page)
          }
          inGerman { session =>
            val page = session.find(classOf[RuntimeSecondaryPage], id)
            assertEquals(page.title, "Initial")
            inGerman(_.find(classOf[RuntimeSecondaryPage], id).title = "Extern")
            session.refresh(page)
            assertEquals(page.title, "Extern")
            page.title = "Nach Refresh"
          }
          inGerman { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], id).title,
              "Nach Refresh"
            )
          }
          inGerman { session =>
            val old = session.find(classOf[RuntimeSecondaryPage], id)
            session.clear()
            assert(!session.contains(old))
            inGerman(_.find(classOf[RuntimeSecondaryPage], id).title = "Nach Clear")
            val reloaded = session.find(classOf[RuntimeSecondaryPage], id)
            assertEquals(reloaded.title, "Nach Clear")
          }
          inGerman { session =>
            val old = session.find(classOf[RuntimeSecondaryPage], id)
            session.evict(old)
            assert(!session.contains(old))
            inGerman(_.find(classOf[RuntimeSecondaryPage], id).title = "Nach Evict")
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], id).title,
              "Nach Evict"
            )
          }
          inGerman { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], id).title,
              "Nach Evict"
            )
          }
          Using.resource(HibernateI18n.openSession(factory, "en")) { session =>
            val transaction = session.beginTransaction()
            try
              val page = session.find(classOf[RuntimeSecondaryPage], id)
              assertEquals(page.title, "Nach Evict")
              inGerman(_.find(classOf[RuntimeSecondaryPage], id).title = "Neuer Fallback")
              assertEquals(page.title, "Nach Evict")
              session.refresh(page)
              assertEquals(page.title, "Neuer Fallback")
              page.title = "English after refresh"
              transaction.commit()
            catch
              case error: Throwable =>
                if transaction.isActive then transaction.rollback()
                throw error
          }
          inGerman { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], id).title,
              "Neuer Fallback"
            )
          }
          Using.resource(HibernateI18n.openSession(factory, "de")) { session =>
            val transaction = session.beginTransaction()
            try
              val page = session.find(classOf[RuntimeSecondaryPage], id)
              val rowId = new util.HashMap[String, Object]()
              rowId.put("pageId", id)
              rowId.put("locale", "de")
              val row = session.find(
                TranslationMappingXml.translationEntityName(
                  classOf[RuntimeSecondaryPage]
                ),
                rowId
              )
                .asInstanceOf[util.Map[String, Object]]
              row.put("title", "Unsaved editor change")
              val error = intercept[HibernateException](session.refresh(page))
              assert(error.getMessage.contains("modified translation row"))
            finally
              transaction.rollback()
          }
          inGerman { session =>
            assertEquals(
              session.find(classOf[RuntimeSecondaryPage], id).title,
              "Neuer Fallback"
            )
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
