package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import org.hibernate.{Session, SessionFactory}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Isolates the shared-table tenant boundary in the annotation-derived Map mapping. */
class TenantIsolationExperimentSuite extends TestPostgres:
  private def inTenant[A](factory: SessionFactory, tenant: String)(body: Session => A): A =
    Using.resource(factory.withOptions().tenantIdentifier(tenant.asInstanceOf[Object])
      .statementInspector(new FixedLocaleSqlInspector("de")).openSession()) { session =>
      SessionContentLocale.bind(session, "de")
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

  test("generated translation rows and formulas respect the parent tenant boundary") {
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
          .addAnnotatedClassName(classOf[TenantLocalizedPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val listenerRegistry = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
          listenerRegistry.appendListeners(
            EventType.PRE_DELETE,
            new TranslationCascadeEvictor[TenantLocalizedPage](
              classOf[TenantLocalizedPage],
              "TenantLocalizedPageTranslation",
              _.id
            )
          )
          val tenantAId = util.UUID.randomUUID()
          val tenantBId = util.UUID.randomUUID()
          val foreignParentId = util.UUID.randomUUID()
          def key(id: util.UUID): util.HashMap[String, Object] =
            val result = new util.HashMap[String, Object]()
            result.put("pageId", id)
            result.put("locale", "de")
            result
          inTenant(factory, "tenant-a") { session =>
            val page = new TenantLocalizedPage()
            page.id = tenantAId
            session.persist(page)
            val row = key(tenantAId)
            row.put("title", "Tenant A")
            session.persist("TenantLocalizedPageTranslation", row)
            val foreignParent = new TenantLocalizedPage()
            foreignParent.id = foreignParentId
            session.persist(foreignParent)
          }
          inTenant(factory, "tenant-b") { session =>
            assert(session.find(classOf[TenantLocalizedPage], tenantAId) == null)
            assert(session.find("TenantLocalizedPageTranslation", key(tenantAId)) == null)
            val page = new TenantLocalizedPage()
            page.id = tenantBId
            session.persist(page)
            val row = key(tenantBId)
            row.put("title", "Tenant B")
            session.persist("TenantLocalizedPageTranslation", row)
          }
          val mismatch = intercept[Exception] {
            inTenant(factory, "tenant-b") { session =>
              val row = key(foreignParentId)
              row.put("title", "Wrong tenant")
              session.persist("TenantLocalizedPageTranslation", row)
              session.flush()
            }
          }
          val errorChain = Iterator.iterate(mismatch: Throwable)(_.getCause)
            .takeWhile(_ != null).flatMap(error => Option(error.getMessage))
          assert(errorChain.exists(_.contains("fk_tenant_page_translation_tenant_page")))
          inTenant(factory, "tenant-a") { session =>
            assertEquals(session.find(classOf[TenantLocalizedPage], tenantAId).title, "Tenant A")
            assert(session.find(classOf[TenantLocalizedPage], tenantBId) == null)
            assert(session.find("TenantLocalizedPageTranslation", key(tenantBId)) == null)
            val ownRow = session.find("TenantLocalizedPageTranslation", key(tenantAId))
              .asInstanceOf[util.Map[String, Object]]
            assertEquals(ownRow.get("tenantId"), "tenant-a")
            assertEquals(
              session.createQuery("from TenantLocalizedPageTranslation", classOf[util.Map[?, ?]])
                .getResultList.size(),
              1
            )
          }
          inTenant(factory, "tenant-b") { session =>
            assertEquals(session.find(classOf[TenantLocalizedPage], tenantBId).title, "Tenant B")
            assert(session.find("TenantLocalizedPageTranslation", key(tenantAId)) == null)
            val ownRow = session.find("TenantLocalizedPageTranslation", key(tenantBId))
              .asInstanceOf[util.Map[String, Object]]
            assertEquals(ownRow.get("tenantId"), "tenant-b")
            assertEquals(
              session.createQuery("from TenantLocalizedPageTranslation", classOf[util.Map[?, ?]])
                .getResultList.size(),
              1
            )
          }
          def cachedTitles(tenant: String): List[String] =
            inTenant(factory, tenant) { session =>
              session.createQuery(
                "select p.title from TenantLocalizedPage p where p.title is not null",
                classOf[String]
              ).setCacheable(true).getResultList.asScala.toList
            }
          factory.getStatistics.clear()
          assertEquals(cachedTitles("tenant-a"), List("Tenant A"))
          assertEquals(cachedTitles("tenant-b"), List("Tenant B"))
          assertEquals(cachedTitles("tenant-a"), List("Tenant A"))
          assertEquals(cachedTitles("tenant-b"), List("Tenant B"))
          assertEquals(factory.getStatistics.getQueryCachePutCount, 2L)
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 2L)
          inTenant(factory, "tenant-a") { session =>
            assert(session.find("TenantLocalizedPageTranslation", key(tenantAId)) != null)
            session.remove(session.find(classOf[TenantLocalizedPage], tenantAId))
            session.flush()
            assert(session.find("TenantLocalizedPageTranslation", key(tenantAId)) == null)
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from tenant_page_translation where page_id = '$tenantAId'"),
            "0"
          )
          assertEquals(
            scalar(dataSource, s"select count(*) from tenant_page_translation where page_id = '$tenantBId'"),
            "1"
          )
          assertEquals(cachedTitles("tenant-a"), Nil)
          assertEquals(cachedTitles("tenant-b"), List("Tenant B"))
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
