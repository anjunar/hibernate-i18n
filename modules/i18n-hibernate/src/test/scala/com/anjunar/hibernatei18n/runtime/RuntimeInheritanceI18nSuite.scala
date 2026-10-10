package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.{Column, DiscriminatorValue, Entity, Id, Inheritance, InheritanceType, MappedSuperclass, Table, Version}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.annotations.TenantId
import org.hibernate.MappingException

import java.nio.file.Files
import java.util.UUID
import scala.compiletime.uninitialized
import scala.util.Using
class RuntimeInheritanceI18nSuite extends munit.FunSuite:
  test("mapped-superclass identity and translations work alongside entity-subclass translation") {
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-development-inheritance-pg-"))
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
          .addAnnotatedClassName(classOf[RuntimeInheritedIdentityPage].getName)
          .addAnnotatedClassName(classOf[RuntimeInheritedTranslationPage].getName)
          .addAnnotatedClassName(classOf[TranslationFieldRoot].getName)
          .addAnnotatedClassName(classOf[RuntimeTextField].getName)
          .addAnnotatedClassName(classOf[RuntimeMarkdownField].getName)
          .buildMetadata()
        assertEquals(
          metadata.getEntityBinding(classOf[RuntimeInheritedIdentityPage].getName)
            .getTable.getColumn(new org.hibernate.mapping.Column("title")),
          null
        )
        assertEquals(
          metadata.getEntityBinding(classOf[RuntimeInheritedTranslationPage].getName)
            .getTable.getColumn(new org.hibernate.mapping.Column("label")),
          null
        )
        assertEquals(
          metadata.getEntityBinding(classOf[TranslationFieldRoot].getName)
            .getTable.getColumn(new org.hibernate.mapping.Column("value")),
          null
        )
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val titleTranslations = HibernateI18n.translations(factory, classOf[RuntimeInheritedIdentityPage])
          val titleField = titleTranslations.field[String]("title")
          val page = new RuntimeInheritedIdentityPage()
          val inherited = new RuntimeInheritedTranslationPage()
          val markdown = new RuntimeMarkdownField()
          markdown.fieldKey = "description"
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
            page.title = "Deutsch"
            inherited.label = "Geerbter Wert"
            inherited.caption = "Eigener Wert"
            markdown.value = "**Deutsch**"
            session.persist(page)
            session.persist(inherited)
            session.persist(markdown)
          }
          inLocale("de") { session =>
            val loaded = session.find(classOf[RuntimeInheritedIdentityPage], page.id)
            assertEquals(loaded.title, "Deutsch")
            assertEquals(loaded.tenant, "tenant-a")
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).label,
              "Geerbter Wert"
            )
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).caption,
              "Eigener Wert"
            )
            val markdownLoaded = session.find(classOf[RuntimeMarkdownField], markdown.id)
            assertEquals(markdownLoaded.value, "**Deutsch**")
            assertEquals(markdownLoaded.tenant, "tenant-a")
            titleTranslations.set(session, loaded, titleField, "en", "English")
          }
          inLocale("en") { session =>
            assertEquals(
              session.find(classOf[RuntimeInheritedIdentityPage], page.id).title,
              "English"
            )
            val inheritedLoaded = session.find(
              classOf[RuntimeInheritedTranslationPage],
              inherited.id
            )
            assertEquals(inheritedLoaded.label, "Geerbter Wert")
            assertEquals(inheritedLoaded.caption, "Eigener Wert")
            inheritedLoaded.label = "Inherited value"
            val loaded = session.find(classOf[RuntimeMarkdownField], markdown.id)
            assertEquals(loaded.value, "**Deutsch**")
            loaded.value = "**English**"
          }
          inLocale("de") { session =>
            assertEquals(
              session.find(classOf[RuntimeInheritedIdentityPage], page.id).title,
              "Deutsch"
            )
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).label,
              "Geerbter Wert"
            )
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).caption,
              "Eigener Wert"
            )
            assertEquals(
              session.find(classOf[RuntimeMarkdownField], markdown.id).value,
              "**Deutsch**"
            )
          }
          inLocale("en") { session =>
            assertEquals(
              session.find(classOf[RuntimeMarkdownField], markdown.id).value,
              "**English**"
            )
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).label,
              "Inherited value"
            )
            assertEquals(
              session.find(
                classOf[RuntimeInheritedTranslationPage],
                inherited.id
              ).caption,
              "Eigener Wert"
            )
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("a translated mapped superclass cannot be shared with another entity mapping") {
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val error = intercept[MappingException] {
        new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeInheritedTranslationPage].getName)
          .addAnnotatedClassName(classOf[RuntimeUnlocalizedSharedPage].getName)
          .buildMetadata()
      }
      assert(error.getMessage.contains("shared by multiple entity mappings"))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("an unlocalized entity cannot silently map inherited translation into its parent table") {
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val error = intercept[MappingException] {
        new MetadataSources(registry)
          .addAnnotatedClassName(classOf[RuntimeUnlocalizedSharedPage].getName)
          .buildMetadata()
      }
      assert(error.getMessage.contains("requires a mapped @Localized entity owner"))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }
