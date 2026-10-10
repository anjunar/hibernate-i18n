package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import org.hibernate.{HibernateException, MappingException, SessionFactory}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.engine.spi.{SessionFactoryImplementor, SharedSessionContractImplementor}

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Reproduces why the formula/StatementInspector prototype cannot use Hibernate's query cache. */
class QueryCacheIsolationExperimentSuite extends TestPostgres:
  private def withoutProductionGuard: ClassLoaderServiceImpl =
    new ClassLoaderServiceImpl():
      override def loadJavaServices[S](contract: Class[S]): util.Collection[S] =
        val discovered = super.loadJavaServices(contract)
        if contract == classOf[AdditionalMappingContributor] then
          discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
        else discovered

  private def rejectedCacheMapping(entity: Class[?], queryCache: Boolean): String =
    val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
    val registry = new StandardServiceRegistryBuilder(bootstrap)
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.cache.region.factory_class", classOf[InMemoryCacheRegionFactory].getName)
      .applySetting("hibernate.cache.use_query_cache", queryCache.toString)
      .build()
    try
      intercept[MappingException] {
        new MetadataSources(registry).addAnnotatedClassName(entity.getName).buildMetadata()
      }.getMessage
    finally StandardServiceRegistryBuilder.destroy(registry)

  test("experimental mapping refuses the query cache before SessionFactory construction") {
    assert(rejectedCacheMapping(classOf[LocalizedPage], queryCache = true).contains("locale-aware query cache region"))
  }

  test("experimental mapping refuses a cached localized entity") {
    assert(rejectedCacheMapping(
      classOf[CachedLocalizedPage],
      queryCache = false
    ).contains("must not use second-level caching"))
  }

  private def title(factory: SessionFactory, locale: String, id: util.UUID, cached: Boolean): String =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
        SessionContentLocale.bind(session, locale)
        val transaction = session.beginTransaction()
        try
          val result = session.createQuery("select p.title from LocalizedPage p where p.id = :id", classOf[String])
            .setParameter("id", id)
            .setCacheable(cached)
            .getSingleResult
          transaction.commit()
          result
        catch
          case error: Throwable =>
            if transaction.isActive then transaction.rollback()
            throw error
    }

  private def entityTitle(factory: SessionFactory, locale: String, id: util.UUID): String =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
        SessionContentLocale.bind(session, locale)
        session.createQuery("select p from LocalizedPage p where p.id = :id", classOf[LocalizedPage])
          .setParameter("id", id)
          .setCacheable(true)
          .getSingleResult.title
    }

  private def seedLocalizedPage(factory: SessionFactory): util.UUID =
    val id = util.UUID.randomUUID()
    factory.inTransaction { session =>
      val page = new LocalizedPage()
      page.id = id
      page.slug = "cache-probe"
      session.persist(page)
      for (locale, translatedTitle) <- List(("de", "Hallo"), ("en", "Hello")) do
        val row = new util.HashMap[String, Object]()
        row.put("pageId", id)
        row.put("locale", locale)
        row.put("title", translatedTitle)
        row.put("content", translatedTitle)
        session.persist("LocalizedPageTranslation", row)
    }
    id

  test("query cache reuses a German formula result for an English Session") {
    withDatabase { dataSource =>
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.region.factory_class", classOf[InMemoryCacheRegionFactory].getName)
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "true")
        .applySetting("hibernate.i18n.experimental.allow_unsafe_cache_probe", "true")
        .applySetting("hibernate.generate_statistics", "true")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[LocalizedPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = seedLocalizedPage(factory)

          assertEquals(title(factory, "de", id, cached = true), "Hallo")
          assertEquals(factory.getStatistics.getQueryCachePutCount, 1L)
          assertEquals(title(factory, "en", id, cached = true), "Hallo")
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 1L)
          assertEquals(title(factory, "en", id, cached = false), "Hello")
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("locale-aware query region keeps cached locales separate and invalidates edited rows") {
    withDatabase { dataSource =>
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
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
          .addAnnotatedClassName(classOf[LocalizedPage].getName)
          .addAnnotatedClassName(classOf[CachedPlainPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = seedLocalizedPage(factory)
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
            mismatched =>
              val error = intercept[IllegalArgumentException](SessionContentLocale.bind(mismatched, "en"))
              assert(error.getMessage.contains("does not match"))
              SessionContentLocale.bind(mismatched, "de")
              mismatched.clear()
              assertEquals(
                SessionContentLocale.required(mismatched.asInstanceOf[SharedSessionContractImplementor]),
                "de"
              )
              intercept[IllegalStateException](SessionContentLocale.bind(mismatched, "en"))
          }
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
            unbound =>
              val error = intercept[HibernateException] {
                unbound.createQuery("select p.title from LocalizedPage p where p.id = :id", classOf[String])
                  .setParameter("id", id)
                  .setCacheable(true)
                  .getSingleResult
              }
              assert(error.getMessage.contains("No content locale bound"))
          }
          factory.getStatistics.clear()

          assertEquals(title(factory, "de", id, cached = true), "Hallo")
          assertEquals(title(factory, "en", id, cached = true), "Hello")
          assertEquals(title(factory, "fr", id, cached = true), "Hello")
          assertEquals(factory.getStatistics.getQueryCachePutCount, 3L)
          assertEquals(title(factory, "de", id, cached = true), "Hallo")
          assertEquals(title(factory, "en", id, cached = true), "Hello")
          assertEquals(title(factory, "fr", id, cached = true), "Hello")
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 3L)
          assertEquals(entityTitle(factory, "de", id), "Hallo")
          assertEquals(entityTitle(factory, "en", id), "Hello")
          assertEquals(entityTitle(factory, "de", id), "Hallo")
          assertEquals(entityTitle(factory, "en", id), "Hello")

          factory.inTransaction { session =>
            val rowId = new util.HashMap[String, Object]()
            rowId.put("pageId", id)
            rowId.put("locale", "en")
            val row = session.find("LocalizedPageTranslation", rowId)
              .asInstanceOf[util.Map[String, Object]]
            row.put("title", "Updated")
          }
          assertEquals(title(factory, "en", id, cached = true), "Updated")
          assertEquals(title(factory, "fr", id, cached = true), "Updated")
          assertEquals(title(factory, "de", id, cached = true), "Hallo")
          assertEquals(entityTitle(factory, "en", id), "Updated")
          assertEquals(entityTitle(factory, "de", id), "Hallo")

          val plainId = util.UUID.randomUUID()
          factory.inTransaction { session =>
            val plain = new CachedPlainPage()
            plain.id = plainId
            plain.label = "ordinary"
            session.persist(plain)
          }
          factory.getCache.evictEntityData(classOf[CachedPlainPage], plainId)
          factory.getStatistics.clear()
          factory.inTransaction { session =>
            assertEquals(session.find(classOf[CachedPlainPage], plainId).label, "ordinary")
          }
          factory.inTransaction { session =>
            assertEquals(session.find(classOf[CachedPlainPage], plainId).label, "ordinary")
          }
          assertEquals(factory.getStatistics.getSecondLevelCacheHitCount, 1L)

          val queryRegion = factory.unwrap(classOf[SessionFactoryImplementor]).getCache
            .getQueryResultsCache(null).getRegion
          Using.resource(factory.withOptions().tenantIdentifier("tenant-a".asInstanceOf[Object])
            .statementInspector(new FixedLocaleSqlInspector("de")).openSession()) { firstTenant =>
            Using.resource(factory.withOptions().tenantIdentifier("tenant-b".asInstanceOf[Object])
              .statementInspector(new FixedLocaleSqlInspector("de")).openSession()) { secondTenant =>
              SessionContentLocale.bind(firstTenant, "de")
              SessionContentLocale.bind(secondTenant, "de")
              val first = firstTenant.asInstanceOf[SharedSessionContractImplementor]
              val second = secondTenant.asInstanceOf[SharedSessionContractImplementor]
              queryRegion.putIntoCache("tenant-probe", "first", first)
              queryRegion.putIntoCache("tenant-probe", "second", second)
              assertEquals(queryRegion.getFromCache("tenant-probe", first), "first")
              assertEquals(queryRegion.getFromCache("tenant-probe", second), "second")
            }
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("second-level entity cache reuses a German formula state for an English Session") {
    withDatabase { dataSource =>
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.region.factory_class", classOf[InMemoryCacheRegionFactory].getName)
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .applySetting("hibernate.i18n.experimental.allow_unsafe_cache_probe", "true")
        .applySetting("hibernate.generate_statistics", "true")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[CachedLocalizedPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          factory.inTransaction { session =>
            val page = new CachedLocalizedPage()
            page.id = id
            session.persist(page)
            for (locale, translatedTitle) <- List(("de", "Hallo"), ("en", "Hello")) do
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", locale)
              row.put("title", translatedTitle)
              session.persist("CachedLocalizedPageTranslation", row)
          }
          factory.getCache.evictEntityData(classOf[CachedLocalizedPage], id)
          factory.getStatistics.clear()

          def load(locale: String): String =
            Using.resource(
              factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()
            ) { session =>
              session.find(classOf[CachedLocalizedPage], id).title
            }

          assertEquals(load("de"), "Hallo")
          assertEquals(factory.getStatistics.getSecondLevelCachePutCount, 1L)
          assertEquals(load("en"), "Hallo")
          assertEquals(factory.getStatistics.getSecondLevelCacheHitCount, 1L)
          factory.getCache.evictEntityData(classOf[CachedLocalizedPage], id)
          assertEquals(load("en"), "Hello")
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
