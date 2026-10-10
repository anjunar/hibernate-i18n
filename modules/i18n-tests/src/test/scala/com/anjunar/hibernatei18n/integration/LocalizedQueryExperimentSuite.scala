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

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Exercises ordering and pagination over ordinary translated fields. */
class LocalizedQueryExperimentSuite extends TestPostgres:
  private def inLocaleTransaction[A](
    factory: SessionFactory,
    locale: String,
    synchronizer: MapTranslationSynchronizer[LocalizedPage]
  )(body: Session => A): A =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
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

  test("HQL and Criteria sort and page by the active translation with per-field fallback") {
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
        val metadata =
          new MetadataSources(registry).addAnnotatedClassName(classOf[LocalizedPage].getName).buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val synchronizer = new MapTranslationSynchronizer[LocalizedPage](
            classOf[LocalizedPage],
            "LocalizedPageTranslation",
            _.id,
            _.title,
            _.content
          )
          val listenerRegistry = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
          listenerRegistry.prependListeners(EventType.FLUSH, synchronizer)
          listenerRegistry.prependListeners(EventType.AUTO_FLUSH, synchronizer)
          listenerRegistry.appendListeners(EventType.POST_LOAD, synchronizer)
          def inLocale[A](locale: String)(body: Session => A): A =
            inLocaleTransaction(factory, locale, synchronizer)(body)
          inLocale("de") { session =>
            for (slug, german, english) <- List(
                ("alpha", "Zebra", Some("Apple")),
                ("beta", "Apfel", Some("Pear")),
                ("gamma", "Mitte", None),
                ("delta", "Birne", Some("Banana")),
                ("epsilon", "Ohne", None)
              )
            do
              val id = util.UUID.randomUUID()
              val page = new LocalizedPage()
              page.id = id
              page.slug = slug
              session.persist(page)
              val germanRow = new util.HashMap[String, Object]()
              germanRow.put("pageId", id)
              germanRow.put("locale", "de")
              germanRow.put("title", german)
              session.persist("LocalizedPageTranslation", germanRow)
              english.foreach { title =>
                val englishRow = new util.HashMap[String, Object]()
                englishRow.put("pageId", id)
                englishRow.put("locale", "en")
                englishRow.put("title", title)
                session.persist("LocalizedPageTranslation", englishRow)
              }
          }
          def hqlPage(locale: String): List[String] = inLocale(locale) { session =>
            session.createQuery("from LocalizedPage p order by p.title, p.slug", classOf[LocalizedPage])
              .setFirstResult(0).setMaxResults(2).setCacheable(true)
              .getResultList.asScala.map(_.slug).toList
          }
          def criteriaPage(locale: String): List[String] = inLocale(locale) { session =>
            val builder = session.getCriteriaBuilder
            val criteria = builder.createQuery(classOf[LocalizedPage])
            val root = criteria.from(classOf[LocalizedPage])
            criteria.select(root).orderBy(builder.asc(root.get[String]("title")), builder.asc(root.get[String]("slug")))
            session.createQuery(criteria).setFirstResult(0).setMaxResults(2)
              .getResultList.asScala.map(_.slug).toList
          }
          factory.getStatistics.clear()
          assertEquals(hqlPage("de"), List("beta", "delta"))
          assertEquals(hqlPage("en"), List("alpha", "delta"))
          assertEquals(hqlPage("fr"), List("alpha", "delta"))
          assertEquals(factory.getStatistics.getQueryCachePutCount, 3L)
          assertEquals(hqlPage("de"), List("beta", "delta"))
          assertEquals(hqlPage("en"), List("alpha", "delta"))
          assertEquals(hqlPage("fr"), List("alpha", "delta"))
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 3L)
          assertEquals(criteriaPage("de"), List("beta", "delta"))
          assertEquals(criteriaPage("en"), List("alpha", "delta"))
          inLocale("en") { session =>
            val secondPage =
              session.createQuery("from LocalizedPage p order by p.title, p.slug", classOf[LocalizedPage])
                .setFirstResult(2).setMaxResults(2).getResultList.asScala.map(_.slug).toList
            assertEquals(secondPage, List("gamma", "epsilon"))
            val fallback = session.createQuery("from LocalizedPage p where p.title = :title", classOf[LocalizedPage])
              .setParameter("title", "Mitte").getResultList.asScala.map(_.slug).toList
            assertEquals(fallback, List("gamma"))
          }
          inLocale("en") { session =>
            val pears = session.createQuery("from LocalizedPage p where p.title = :title", classOf[LocalizedPage])
              .setParameter("title", "Pear").getResultList.asScala.map(_.slug).toList
            assertEquals(pears, List("beta"))
          }
          inLocale("de") { session =>
            val pears = session.createQuery("from LocalizedPage p where p.title = :title", classOf[LocalizedPage])
              .setParameter("title", "Pear").getResultList.asScala.map(_.slug).toList
            assertEquals(pears, Nil)
          }
          inLocale("en") { session =>
            val page = session.createQuery("from LocalizedPage p where p.slug = :slug", classOf[LocalizedPage])
              .setParameter("slug", "beta").getSingleResult
            page.title = "Aardvark"
          }
          assertEquals(hqlPage("en"), List("beta", "alpha"))
          assertEquals(hqlPage("de"), List("beta", "delta"))
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
