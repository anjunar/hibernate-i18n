package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Cacheable, Entity, Id, Table}
import org.hibernate.annotations.{Cache, CacheConcurrencyStrategy}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeStackNamesI18nSuite extends munit.FunSuite:
  test("hash table names and legacy Translation entities coexist with generated rows") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-stack-names-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      val registry = HibernateI18n.registryBuilder()
        .applySetting("hibernate.connection.datasource", postgres.getPostgresDatabase)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.cache.region.factory_class",
          classOf[RuntimeInMemoryCacheRegionFactory].getName)
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeStackOffering].getName)
          .addAnnotatedClassName(classOf[RuntimeStackOfferingTranslation].getName)
          .addAnnotatedClassName(classOf[RuntimeStackEvent].getName)
          .addAnnotatedClassName(classOf[RuntimeStackEventTranslation].getName)
          .buildMetadata()
        for (entity, legacy) <- Seq(
          (classOf[RuntimeStackOffering], classOf[RuntimeStackOfferingTranslation]),
          (classOf[RuntimeStackEvent], classOf[RuntimeStackEventTranslation])
        ) do
          val generatedName = TranslationMappingXml.translationEntityName(entity)
          assert(generatedName != legacy.getSimpleName)
          assert(metadata.getEntityBinding(generatedName) != null)
          assert(metadata.getEntityBinding(legacy.getName) != null)
          assertEquals(metadata.getEntityBinding(entity.getName)
            .getTable.getColumn(new Column("title")), null)
          assert(metadata.getEntityBinding(generatedName).getTable.isQuoted)
        assert(!metadata.getEntityBinding(classOf[RuntimeStackEvent].getName).isCached)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          HibernateI18n.install(factory, classOf[RuntimeStackOffering], _.id,
            Seq(TranslationField.string[RuntimeStackOffering]("title", _.title)))
          HibernateI18n.install(factory, classOf[RuntimeStackEvent], _.id,
            Seq(TranslationField.string[RuntimeStackEvent]("title", _.title)))
          val offering = new RuntimeStackOffering()
          val event = new RuntimeStackEvent()
          val legacyOffering = new RuntimeStackOfferingTranslation()
          legacyOffering.id = UUID.randomUUID()
          legacyOffering.locale = "de"
          legacyOffering.title = "Legacy-Angebot"
          val legacyEvent = new RuntimeStackEventTranslation()
          legacyEvent.id = UUID.randomUUID()
          legacyEvent.locale = "de"
          legacyEvent.title = "Legacy-Termin"
          def inLocale[A](locale: String)(body: org.hibernate.Session => A): A =
            Using.resource(HibernateI18n.openSession(factory, locale, "tenant-a")) { session =>
              val tx = session.beginTransaction()
              try
                val result = body(session)
                tx.commit()
                result
              catch
                case error: Throwable =>
                  if tx.isActive then tx.rollback()
                  throw error
            }
          inLocale("de") { session =>
            offering.title = "Angebot"
            event.title = "Termin"
            session.persist(offering)
            session.persist(event)
            session.persist(legacyOffering)
            session.persist(legacyEvent)
          }
          inLocale("en") { session =>
            val loadedOffering = session.find(classOf[RuntimeStackOffering], offering.id)
            val loadedEvent = session.find(classOf[RuntimeStackEvent], event.id)
            assertEquals(loadedOffering.title, "Angebot")
            assertEquals(loadedEvent.title, "Termin")
            loadedOffering.title = "Offering"
            loadedEvent.title = "Event"
            assertEquals(session.createQuery(
              "select o.title from RuntimeStackOffering o where o.id = :id", classOf[String]
            ).setParameter("id", offering.id).getSingleResult, "Offering")
            assertEquals(session.createQuery(
              "select e.title from RuntimeStackEvent e where e.id = :id", classOf[String]
            ).setParameter("id", event.id).getSingleResult, "Event")
          }
          assert(!factory.getCache.containsEntity(classOf[RuntimeStackEvent], event.id))
          inLocale("de") { session =>
            assertEquals(session.find(classOf[RuntimeStackOffering], offering.id).title,
              "Angebot")
            assertEquals(session.find(classOf[RuntimeStackEvent], event.id).title,
              "Termin")
            assertEquals(session.find(classOf[RuntimeStackOfferingTranslation],
              legacyOffering.id).title, "Legacy-Angebot")
            assertEquals(session.find(classOf[RuntimeStackEventTranslation],
              legacyEvent.id).title, "Legacy-Termin")
          }
          inLocale("en") { session =>
            assertEquals(session.find(classOf[RuntimeStackOffering], offering.id).title,
              "Offering")
            assertEquals(session.find(classOf[RuntimeStackEvent], event.id).title,
              "Event")
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
