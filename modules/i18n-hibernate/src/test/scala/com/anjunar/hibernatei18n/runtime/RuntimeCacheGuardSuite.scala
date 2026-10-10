package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Cacheable, Entity, Id, Inheritance, InheritanceType, Table}
import org.hibernate.MappingException
import org.hibernate.annotations.{Cache, CacheConcurrencyStrategy}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.nio.file.Files
import java.util
import scala.compiletime.uninitialized
import scala.util.Using
import java.nio.file.Path
import munit.FunSuite
import org.hibernate.Session
class RuntimeCacheGuardSuite extends FunSuite:
  private def rejected(entity: Class[?], queryCache: Boolean, sharedCacheMode: String = "UNSPECIFIED"): String =
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.cache.region.factory_class", "org.hibernate.cache.internal.NoCachingRegionFactory")
      .applySetting("hibernate.cache.use_second_level_cache", "true")
      .applySetting("hibernate.cache.use_query_cache", queryCache.toString)
      .applySetting("jakarta.persistence.sharedCache.mode", sharedCacheMode)
      .build()
    try
      intercept[MappingException] {
        new MetadataSources(registry).addAnnotatedClassName(entity.getName).buildMetadata()
      }.getMessage
    finally StandardServiceRegistryBuilder.destroy(registry)

  private def uncached(entity: Class[?], sharedCacheMode: String): Boolean =
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.cache.region.factory_class", "org.hibernate.cache.internal.NoCachingRegionFactory")
      .applySetting("hibernate.cache.use_second_level_cache", "true")
      .applySetting("jakarta.persistence.sharedCache.mode", sharedCacheMode)
      .build()
    try
      val parent = new MetadataSources(registry).addAnnotatedClassName(entity.getName)
        .buildMetadata().getEntityBinding(entity.getName)
      !parent.isCached && parent.getRootClass.getCacheConcurrencyStrategy == null
    finally StandardServiceRegistryBuilder.destroy(registry)

  test("runtime bootstrap rejects the query cache before building a SessionFactory") {
    assert(rejected(classOf[RuntimeSecondaryPage], queryCache = true)
      .contains("does not support query caching"))
  }

  test("runtime bootstrap disables second-level caching of a localized entity") {
    assert(uncached(classOf[RuntimeCachedPage], "UNSPECIFIED"))
  }

  test("runtime bootstrap excludes a localized entity under JPA cache mode ALL") {
    assert(uncached(classOf[RuntimeSecondaryPage], "ALL"))
  }

  test("runtime bootstrap disables caching across a localized entity hierarchy") {
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.cache.region.factory_class", "org.hibernate.cache.internal.NoCachingRegionFactory")
      .applySetting("hibernate.cache.use_second_level_cache", "true")
      .build()
    try
      val metadata = new MetadataSources(registry)
        .addAnnotatedClassName(classOf[RuntimeCachedRoot].getName)
        .addAnnotatedClassName(classOf[RuntimeCachedChild].getName)
        .buildMetadata()
      val root = metadata.getEntityBinding(classOf[RuntimeCachedRoot].getName)
      val child = metadata.getEntityBinding(classOf[RuntimeCachedChild].getName)
      assert(!root.isCached)
      assert(!child.isCached)
      assertEquals(root.getRootClass.getCacheConcurrencyStrategy, null)
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("a cacheable localized entity stays locale-correct while ordinary entity caching works") {
    val target = Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-cache-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting(
          "hibernate.cache.region.factory_class",
          classOf[RuntimeInMemoryCacheRegionFactory].getName
        )
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .applySetting("hibernate.generate_statistics", "true")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeCachedPage].getName)
          .addAnnotatedClassName(classOf[RuntimePlainCachedPage].getName)
          .buildMetadata()
        assert(!metadata.getEntityBinding(classOf[RuntimeCachedPage].getName).isCached)
        assert(metadata.getEntityBinding(classOf[RuntimePlainCachedPage].getName).isCached)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          HibernateI18n.install(
            factory,
            classOf[RuntimeCachedPage],
            _.id,
            Seq(TranslationField.string[RuntimeCachedPage]("title", _.title))
          )
          val id = util.UUID.randomUUID()
          val plainId = util.UUID.randomUUID()
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
            val page = new RuntimeCachedPage()
            page.id = id
            page.title = "Hallo"
            val plain = new RuntimePlainCachedPage()
            plain.id = plainId
            plain.title = "Cached plain"
            session.persist(page)
            session.persist(plain)
          }
          inLocale("de") { session =>
            assertEquals(session.find(classOf[RuntimeCachedPage], id).title, "Hallo")
            assertEquals(
              session.find(classOf[RuntimePlainCachedPage], plainId).title,
              "Cached plain"
            )
          }
          assert(!factory.getCache.containsEntity(classOf[RuntimeCachedPage], id))
          assert(factory.getCache.containsEntity(classOf[RuntimePlainCachedPage], plainId))
          inLocale("en") { session =>
            val page = session.find(classOf[RuntimeCachedPage], id)
            assertEquals(page.title, "Hallo")
            page.title = "Hello"
          }
          inLocale("de") { session =>
            assertEquals(session.find(classOf[RuntimeCachedPage], id).title, "Hallo")
          }
          inLocale("en") { session =>
            assertEquals(session.find(classOf[RuntimeCachedPage], id).title, "Hello")
          }
          assert(!factory.getCache.containsEntity(classOf[RuntimeCachedPage], id))
          assert(factory.getStatistics.getSecondLevelCacheHitCount > 0)
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
