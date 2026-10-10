package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, Entity, Id, Table}
import org.hibernate.Session
import org.hibernate.annotations.TenantId
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.util.Using
import java.nio.file.Path
import munit.FunSuite
class RuntimeTenantI18nSuite extends FunSuite:
  test("development editor and domain paths respect the Session tenant") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-tenant-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val factory = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeTenantPage].getName)
          .buildMetadata().buildSessionFactory()
        Using.resource(factory) { _ =>
          val idA = util.UUID.randomUUID()
          val idB = util.UUID.randomUUID()
          def inTenant[A](tenant: String, locale: String)(body: Session => A): A =
            Using.resource(HibernateI18n.openSession(factory, locale, tenant)) { session =>
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
          inTenant("tenant-a", "de") { session =>
            val page = new RuntimeTenantPage()
            page.id = idA
            page.title = "Deutsch A"
            session.persist(page)
          }
          inTenant("tenant-b", "de") { session =>
            val page = new RuntimeTenantPage()
            page.id = idB
            page.title = "Deutsch B"
            session.persist(page)
          }
          val translations = HibernateI18n.translations(factory, classOf[RuntimeTenantPage])
          val titleField = translations.field[String]("title")
          val detachedA = inTenant("tenant-a", "de") { session =>
            val page = session.find(classOf[RuntimeTenantPage], idA)
            assertEquals(page.tenantId, "tenant-a")
            translations.set(session, page, titleField, "en", "English A")
            page
          }
          inTenant("tenant-b", "de") { session =>
            assert(session.find(classOf[RuntimeTenantPage], idA) == null)
            val page = session.find(classOf[RuntimeTenantPage], idB)
            assertEquals(page.tenantId, "tenant-b")
            intercept[IllegalArgumentException] {
              translations.get(session, detachedA, titleField, "en")
            }
            assertEquals(translations.get(session, page, titleField, "en"), None)
            translations.set(session, page, titleField, "en", "English B")
            assertEquals(page.title, "Deutsch B")
          }
          inTenant("tenant-a", "en") { session =>
            assertEquals(session.find(classOf[RuntimeTenantPage], idA).title, "English A")
            assert(session.find(classOf[RuntimeTenantPage], idB) == null)
            assertEquals(
              session.createQuery(
                "select p.title from RuntimeTenantPage p",
                classOf[String]
              ).getResultList.asScala.toList,
              List("English A")
            )
          }
          inTenant("tenant-b", "en") { session =>
            assertEquals(session.find(classOf[RuntimeTenantPage], idB).title, "English B")
            assert(session.find(classOf[RuntimeTenantPage], idA) == null)
            assertEquals(
              session.createQuery(
                "select p.title from RuntimeTenantPage p",
                classOf[String]
              ).getResultList.asScala.toList,
              List("English B")
            )
          }
          val draftAId = util.UUID.randomUUID()
          inTenant("tenant-a", "de") { session =>
            val source = session.find(classOf[RuntimeTenantPage], idA)
            val draft = new RuntimeTenantPage()
            draft.id = draftAId
            draft.title = "Entwurf A"
            session.persist(draft)
            assertEquals(translations.copyInactive(session, source, draft), 1)
            assertEquals(draft.title, "Entwurf A")
          }
          inTenant("tenant-a", "en") { session =>
            assertEquals(session.find(classOf[RuntimeTenantPage], draftAId).title, "English A")
          }
          inTenant("tenant-b", "en") { session =>
            assert(session.find(classOf[RuntimeTenantPage], draftAId) == null)
          }
          inTenant("tenant-a", "de") { session =>
            val page = session.find(classOf[RuntimeTenantPage], idA)
            assertEquals(translations.get(session, page, titleField, "en"), Some("English A"))
            session.remove(page)
          }
          inTenant("tenant-b", "en") { session =>
            assertEquals(session.find(classOf[RuntimeTenantPage], idB).title, "English B")
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
