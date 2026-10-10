package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import org.hibernate.{MappingException, Session, SessionFactory}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.{Identifier, PhysicalNamingStrategy, PhysicalNamingStrategySnakeCaseImpl}
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.mapping.{Column as MappingColumn, Formula}
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Verifies that generated SQL uses physical, rather than annotation-level logical, names. */
class PhysicalNamingExperimentSuite extends TestPostgres:
  private final class PrefixSnakeCaseNamingStrategy extends PhysicalNamingStrategySnakeCaseImpl:
    override def toPhysicalTableName(name: Identifier, environment: JdbcEnvironment): Identifier =
      val physical = super.toPhysicalTableName(name, environment)
      if physical == null then null else new Identifier("x_" + physical.getText, false)

    override def toPhysicalColumnName(name: Identifier, environment: JdbcEnvironment): Identifier =
      val physical = super.toPhysicalColumnName(name, environment)
      if physical == null then null else new Identifier("x_" + physical.getText, false)

  private final class QuotedNamingStrategy extends PhysicalNamingStrategySnakeCaseImpl:
    override def toPhysicalTableName(name: Identifier, environment: JdbcEnvironment): Identifier =
      val physical = super.toPhysicalTableName(name, environment)
      if physical == null then null else new Identifier(physical.getText.toUpperCase(util.Locale.ROOT), true)

    override def toPhysicalColumnName(name: Identifier, environment: JdbcEnvironment): Identifier =
      val physical = super.toPhysicalColumnName(name, environment)
      if physical == null then null else new Identifier(physical.getText.toUpperCase(util.Locale.ROOT), true)

  private def inLocale[A](factory: SessionFactory, locale: String)(body: Session => A): A =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
        SessionContentLocale.bind(session, locale)
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

  private def checkNaming(strategy: PhysicalNamingStrategy, prefix: String, quoted: Boolean = false): Unit =
    withDatabase { dataSource =>
      val classLoading = new ClassLoaderServiceImpl():
        override def loadJavaServices[S](contract: Class[S]): util.Collection[S] =
          val discovered = super.loadJavaServices(contract)
          if contract == classOf[AdditionalMappingContributor] then
            discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
          else discovered
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(classLoading).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", strategy)
        .applySetting(
          "hibernate.cache.region.factory_class",
          new LocaleAwareRegionFactory(new InMemoryCacheRegionFactory)
        )
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "true")
        .applySetting("hibernate.generate_statistics", "true")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[NamingLocalizedPage].getName).buildMetadata()
        val parent = metadata.getEntityBinding(classOf[NamingLocalizedPage].getName)
        val translation = metadata.getEntityBinding("NamingLocalizedPageTranslation")
        def physicalName(name: String): String = if quoted then name.toUpperCase(util.Locale.ROOT) else name
        def sqlName(name: String): String = if quoted then s"\"${physicalName(name)}\"" else physicalName(name)
        assertEquals(parent.getTable.getName, physicalName(prefix + "naming_page"))
        assertEquals(translation.getTable.getName, physicalName(prefix + "naming_page_translation"))
        assert(parent.getTable.getColumns.asScala.exists(_.getName == physicalName(prefix + "document_id")))
        assert(translation.getTable.getColumns.asScala.exists(_.getName == physicalName(prefix + "display_title")))
        assert(translation.getTable.getColumns.asScala.exists(_.getName == physicalName(prefix + "row_version")))
        assertEquals(translation.getProperty("rowVersion").getValue.getColumns.get(0).isQuoted, quoted)
        assert(parent.getSynchronizedTables.contains(sqlName(prefix + "naming_page_translation")))
        val formula = parent.getProperty("displayTitle").getValue.getSelectables.get(0).asInstanceOf[Formula].getFormula
        assert(formula.contains(s"from ${sqlName(prefix + "naming_page_translation")} t"))
        assert(formula.contains(s"t.${sqlName(prefix + "display_title")}"))
        assert(formula.contains(s"t.${sqlName(prefix + "page_id")}"))
        assert(formula.contains(s"t.${sqlName(prefix + "locale")}"))
        assert(formula.contains(s"{alias}.${sqlName(prefix + "document_id")}"))
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          inLocale(factory, "de") { session =>
            val page = new NamingLocalizedPage()
            page.id = id
            session.persist(page)
            for (locale, value) <- List(("de", "Hallo"), ("en", "Hello")) do
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", locale)
              row.put("displayTitle", value)
              session.persist("NamingLocalizedPageTranslation", row)
          }
          def cachedTitle(locale: String): String = inLocale(factory, locale) { session =>
            session.createQuery("select p.displayTitle from NamingLocalizedPage p", classOf[String])
              .setCacheable(true).getSingleResult
          }
          factory.getStatistics.clear()
          assertEquals(cachedTitle("de"), "Hallo")
          assertEquals(cachedTitle("en"), "Hello")
          assertEquals(cachedTitle("de"), "Hallo")
          assertEquals(cachedTitle("en"), "Hello")
          assertEquals(factory.getStatistics.getQueryCachePutCount, 2L)
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 2L)
          inLocale(factory, "en") { session =>
            val key = new util.HashMap[String, Object]()
            key.put("pageId", id)
            key.put("locale", "en")
            session.find("NamingLocalizedPageTranslation", key)
              .asInstanceOf[util.Map[String, Object]].put("displayTitle", "Updated")
          }
          assertEquals(cachedTitle("en"), "Updated")
          assertEquals(cachedTitle("de"), "Hallo")
          inLocale(factory, "de") { session =>
            val found = session.createQuery(
              "from NamingLocalizedPage p where p.displayTitle = :title",
              classOf[NamingLocalizedPage]
            )
              .setParameter("title", "Hallo").getSingleResult
            assertEquals(found.id, id)
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }

  test("snake-case naming applies to translation formulas, FK and query-space invalidation") {
    checkNaming(new PhysicalNamingStrategySnakeCaseImpl, "")
  }

  test("custom table and column prefixes also reach the generated formula") {
    checkNaming(new PrefixSnakeCaseNamingStrategy, "x_")
  }

  test("an ordinary @Column on a translated property fails before mapping a parent column") {
    val error = intercept[MappingException] {
      TranslationMappingXml.mappingFor(classOf[ColumnAnnotatedLocalizedPage])
    }
    assert(error.getMessage.contains("@Column on @Translation"))
    assert(error.getMessage.contains("@Translation(column = ...)"))
  }

  test("a translation column cannot collide with an internal key column") {
    val error = intercept[MappingException] {
      TranslationMappingXml.mappingFor(classOf[CollidingTranslationColumnPage])
    }
    assert(error.getMessage.contains("internal key columns"))
    val duplicate = intercept[MappingException] {
      TranslationMappingXml.mappingFor(classOf[DuplicateTranslationColumnPage])
    }
    assert(duplicate.getMessage.contains("distinct from each other"))
  }

  test("physical naming cannot merge translation values with keys, version or each other") {
    withDatabase { dataSource =>
      val classLoading = new ClassLoaderServiceImpl():
        override def loadJavaServices[S](contract: Class[S]): util.Collection[S] =
          val discovered = super.loadJavaServices(contract)
          if contract == classOf[AdditionalMappingContributor] then
            discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
          else discovered
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(classLoading).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.physical_naming_strategy", new PhysicalNamingStrategySnakeCaseImpl)
        .build()
      try
        for entity <- Seq(
            classOf[PhysicalKeyCollisionPage],
            classOf[PhysicalVersionCollisionPage],
            classOf[PhysicalDuplicateColumnPage]
          )
        do
          val error = intercept[MappingException] {
            new MetadataSources(registry).addAnnotatedClassName(entity.getName).buildMetadata()
          }
          val expected = if entity == classOf[PhysicalVersionCollisionPage] then
            "collide after physical naming"
          else "physical column name"
          assert(error.getMessage.contains(expected), s"${entity.getSimpleName}: ${error.getMessage}")
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("@Translation column names map field and getter values into translation rows only") {
    withDatabase { dataSource =>
      val classLoading = new ClassLoaderServiceImpl():
        override def loadJavaServices[S](contract: Class[S]): util.Collection[S] =
          val discovered = super.loadJavaServices(contract)
          if contract == classOf[AdditionalMappingContributor] then
            discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
          else discovered
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(classLoading).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", new PhysicalNamingStrategySnakeCaseImpl)
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[NamedColumnLocalizedPage].getName)
          .addAnnotatedClassName(classOf[NamedPropertyLocalizedPage].getName)
          .buildMetadata()
        for (entityClass, translationName) <- Seq(
            (classOf[NamedColumnLocalizedPage], "NamedColumnLocalizedPageTranslation"),
            (classOf[NamedPropertyLocalizedPage], "NamedPropertyLocalizedPageTranslation")
          )
        do
          val parent = metadata.getEntityBinding(entityClass.getName)
          val translation = metadata.getEntityBinding(translationName)
          assertEquals(parent.getTable.getColumn(new MappingColumn("display_title")), null)
          assertEquals(parent.getTable.getColumn(new MappingColumn("title")), null)
          assert(parent.getProperty("title").getValue.getSelectables.get(0).isInstanceOf[Formula])
          assert(translation.getTable.getColumn(new MappingColumn("display_title")) != null)
          assertEquals(translation.getTable.getColumn(new MappingColumn("title")), null)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val fieldId = util.UUID.randomUUID()
          val getterId = util.UUID.randomUUID()
          inLocale(factory, "de") { session =>
            val fieldPage = new NamedColumnLocalizedPage()
            fieldPage.id = fieldId
            session.persist(fieldPage)
            val getterPage = new NamedPropertyLocalizedPage()
            getterPage.setId(getterId)
            session.persist(getterPage)
            for (id, translationName, title) <- Seq(
                (fieldId, "NamedColumnLocalizedPageTranslation", "Feld"),
                (getterId, "NamedPropertyLocalizedPageTranslation", "Getter")
              )
            do
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", "de")
              row.put("title", title)
              session.persist(translationName, row)
          }
          inLocale(factory, "de") { session =>
            val fieldPage = session.find(classOf[NamedColumnLocalizedPage], fieldId)
            val getterPage = session.find(classOf[NamedPropertyLocalizedPage], getterId)
            assertEquals(fieldPage.title, "Feld")
            assertEquals(getterPage.getTitle, "Getter")
            assertEquals(
              session.createQuery(
                "from NamedColumnLocalizedPage p where p.title = :title",
                classOf[NamedColumnLocalizedPage]
              ).setParameter("title", "Feld").getSingleResult,
              fieldPage
            )
            assertEquals(
              session.createQuery(
                "from NamedPropertyLocalizedPage p where p.title = :title",
                classOf[NamedPropertyLocalizedPage]
              ).setParameter("title", "Getter").getSingleResult,
              getterPage
            )
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("quoted mixed-case table and column names reach formulas and query-space invalidation") {
    checkNaming(new QuotedNamingStrategy, "", quoted = true)
  }
