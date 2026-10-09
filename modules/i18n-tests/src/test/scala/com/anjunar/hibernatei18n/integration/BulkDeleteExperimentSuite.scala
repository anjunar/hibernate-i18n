package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import org.hibernate.{Session, SessionFactory}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType

import java.util.{HashMap, UUID}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Isolates the Persistence Context behavior of HQL bulk deletion. */
class BulkDeleteExperimentSuite extends TestPostgres:
  private def inLocale[A](factory: SessionFactory, locale: String, synchronizer: MapTranslationSynchronizer[LocalizedPage])
    (body: Session => A): A =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) { session =>
      synchronizer.bind(session, locale)
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

  test("HQL bulk delete cascades translation rows but leaves loaded rows managed") {
    withDatabase { dataSource =>
      val classLoading = new ClassLoaderServiceImpl():
        override def loadJavaServices[S](contract: Class[S]): java.util.Collection[S] =
          val discovered = super.loadJavaServices(contract)
          if contract == classOf[AdditionalMappingContributor] then
            discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
          else discovered
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(classLoading).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.region.factory_class", new LocaleAwareRegionFactory(new InMemoryCacheRegionFactory))
        .applySetting("hibernate.cache.use_second_level_cache", "true")
        .applySetting("hibernate.cache.use_query_cache", "true")
        .build()
      try
        val metadata = new MetadataSources(registry).addAnnotatedClassName(classOf[LocalizedPage].getName).buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val synchronizer = new MapTranslationSynchronizer[LocalizedPage](
            classOf[LocalizedPage], "LocalizedPageTranslation", _.id, _.title, _.content
          )
          val listenerRegistry = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
          listenerRegistry.prependListeners(EventType.FLUSH, synchronizer)
          listenerRegistry.prependListeners(EventType.AUTO_FLUSH, synchronizer)
          listenerRegistry.appendListeners(EventType.POST_LOAD, synchronizer)
          listenerRegistry.appendListeners(EventType.PRE_DELETE,
            new TranslationCascadeEvictor[LocalizedPage](classOf[LocalizedPage], "LocalizedPageTranslation", _.id))
          val id = UUID.randomUUID()
          def rowId(locale: String): HashMap[String, Object] =
            val key = new HashMap[String, Object]()
            key.put("pageId", id)
            key.put("locale", locale)
            key
          inLocale(factory, "de", synchronizer) { session =>
            val page = new LocalizedPage()
            page.id = id
            page.slug = "bulk-probe"
            session.persist(page)
            for (locale, title) <- List(("de", "Hallo"), ("en", "Hello")) do
              val row = rowId(locale)
              row.put("title", title)
              session.persist("LocalizedPageTranslation", row)
          }
          def cachedSlugs(): List[String] = inLocale(factory, "de", synchronizer) { session =>
            session.createQuery("select p.slug from LocalizedPage p", classOf[String])
              .setCacheable(true).getResultList.asScala.toList
          }
          assertEquals(cachedSlugs(), List("bulk-probe"))
          inLocale(factory, "de", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            val german = session.find("LocalizedPageTranslation", rowId("de"))
            val english = session.find("LocalizedPageTranslation", rowId("en"))
            assertEquals(page.title, "Hallo")
            assertEquals(session.createMutationQuery("delete from LocalizedPage p where p.id = :id")
              .setParameter("id", id).executeUpdate(), 1)
            assert(session.contains(page))
            assert(session.find("LocalizedPageTranslation", rowId("de")) eq german)
            assert(session.find("LocalizedPageTranslation", rowId("en")) eq english)
            session.clear()
            assert(session.find(classOf[LocalizedPage], id) == null)
            assert(session.find("LocalizedPageTranslation", rowId("de")) == null)
            assert(session.find("LocalizedPageTranslation", rowId("en")) == null)
          }
          assertEquals(scalar(dataSource, s"select count(*) from page_translation where page_id = '$id'"), "0")
          assertEquals(cachedSlugs(), Nil)
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
