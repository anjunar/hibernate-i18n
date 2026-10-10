package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import com.anjunar.hibernatei18n.runtime.SessionContentLocale
import org.hibernate.{FlushMode, HibernateException, MappingException, ReplicationMode, Session, SessionFactory, StaleObjectStateException}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.mapping.Column

import java.util
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*
import scala.util.Using
import org.hibernate.mapping.Formula

/** Isolated proof of a method-annotated translated JavaBean property. */
class PropertyAccessExperimentSuite extends TestPostgres:
  private def withoutProductionGuard: ClassLoaderServiceImpl =
    new ClassLoaderServiceImpl():
      override def loadJavaServices[S](contract: Class[S]): util.Collection[S] =
        val discovered = super.loadJavaServices(contract)
        if contract == classOf[AdditionalMappingContributor] then
          discovered.asScala.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]).toSeq.asJava
        else discovered

  private def inLocale[A](
    factory: SessionFactory,
    locale: String,
    synchronizer: MapTranslationSynchronizer[PropertyLocalizedPage]
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

  private def withGetterMapping[A](body: (
    DataSource,
    SessionFactory,
    MapTranslationSynchronizer[PropertyLocalizedPage]
  ) => A): A =
    withDatabase { dataSource =>
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[PropertyLocalizedPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val synchronizer = new MapTranslationSynchronizer[PropertyLocalizedPage](
            classOf[PropertyLocalizedPage],
            "PropertyLocalizedPageTranslation",
            _.getId,
            _.getTitle,
            _.getContent
          )
          val listeners = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
          listeners.prependListeners(EventType.FLUSH, synchronizer)
          listeners.prependListeners(EventType.AUTO_FLUSH, synchronizer)
          listeners.appendListeners(EventType.CLEAR, synchronizer)
          listeners.appendListeners(EventType.EVICT, synchronizer)
          listeners.appendListeners(EventType.REFRESH, synchronizer)
          listeners.prependListeners(EventType.MERGE, synchronizer)
          listeners.prependListeners(EventType.REPLICATE, synchronizer)
          listeners.appendListeners(EventType.POST_LOAD, synchronizer)
          listeners.appendListeners(
            EventType.PRE_DELETE,
            new TranslationCascadeEvictor[PropertyLocalizedPage](
              classOf[PropertyLocalizedPage],
              "PropertyLocalizedPageTranslation",
              _.getId
            )
          )
          body(dataSource, factory, synchronizer)
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }

  private def seedTwoLocales(
    factory: SessionFactory,
    synchronizer: MapTranslationSynchronizer[PropertyLocalizedPage],
    id: util.UUID
  ): Unit =
    inLocale(factory, "de", synchronizer) { session =>
      val page = new PropertyLocalizedPage()
      page.setId(id)
      page.setTitle("Hallo")
      page.setContent(Markdown("**Deutsch**"))
      session.persist(page)
    }
    inLocale(factory, "en", synchronizer) { session =>
      val page = session.find(classOf[PropertyLocalizedPage], id)
      page.setTitle("Hello")
      page.setContent(Markdown("**English**"))
    }

  private def cachedGetterTitle(factory: SessionFactory, locale: String, id: util.UUID): String =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
        SessionContentLocale.bind(session, locale)
        session.createQuery("select p.title from PropertyLocalizedPage p where p.id = :id", classOf[String])
          .setParameter("id", id)
          .setCacheable(true)
          .getSingleResult
    }

  private def cachedGetterEntityTitle(factory: SessionFactory, locale: String, id: util.UUID): String =
    Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector(locale)).openSession()) {
      session =>
        SessionContentLocale.bind(session, locale)
        session.createQuery("select p from PropertyLocalizedPage p where p.id = :id", classOf[PropertyLocalizedPage])
          .setParameter("id", id)
          .setCacheable(true)
          .getSingleResult.getTitle
    }

  test("mixed getter and field annotations fail before metadata can bind a parent column") {
    val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
    val registry = new StandardServiceRegistryBuilder(bootstrap)
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val error = intercept[MappingException] {
        new MetadataSources(registry).addAnnotatedClassName(classOf[MixedAccessLocalizedPage].getName).buildMetadata()
      }
      assert(error.getMessage.contains("Mixed getter and field access"))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("mapped-superclass translation binds as a formula without a parent column") {
    val xml = TranslationMappingXml.mappingFor(classOf[InheritedLocalizedPage])
    assert(xml.contains(s"<mapped-superclass class=\"${classOf[LocalizedPageBase].getName}\" access=\"FIELD\">"))
    val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
    val registry = new StandardServiceRegistryBuilder(bootstrap)
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val metadata = new MetadataSources(registry)
        .addAnnotatedClassName(classOf[InheritedLocalizedPage].getName).buildMetadata()
      val parent = metadata.getEntityBinding(classOf[InheritedLocalizedPage].getName)
      assertEquals(parent.getTable.getColumn(new Column("title")), null)
      assert(parent.getProperty("title").getValue.getSelectables.get(0)
        .isInstanceOf[Formula])
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("inherited JavaBean translation binds through property access") {
    val xml = TranslationMappingXml.mappingFor(classOf[InheritedGetterLocalizedPage])
    assert(
      xml.contains(s"<mapped-superclass class=\"${classOf[LocalizedGetterPageBase].getName}\" access=\"PROPERTY\">")
    )
    val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
    val registry = new StandardServiceRegistryBuilder(bootstrap)
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val metadata = new MetadataSources(registry)
        .addAnnotatedClassName(classOf[InheritedGetterLocalizedPage].getName).buildMetadata()
      val parent = metadata.getEntityBinding(classOf[InheritedGetterLocalizedPage].getName)
      assertEquals(parent.getTable.getColumn(new Column("title")), null)
      assert(parent.getProperty("title").getValue.getSelectables.get(0)
        .isInstanceOf[Formula])
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("getter-annotated properties keep their types and load translations without parent columns") {
    withDatabase { dataSource =>
      val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(withoutProductionGuard).build()
      val registry = new StandardServiceRegistryBuilder(bootstrap)
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClassName(classOf[PropertyLocalizedPage].getName)
          .buildMetadata()
        val parent = metadata.getEntityBinding(classOf[PropertyLocalizedPage].getName)
        assert(parent.hasProperty("title"))
        assert(parent.hasProperty("content"))
        assertEquals(parent.getTable.getColumn(new Column("title")), null)
        assertEquals(parent.getTable.getColumn(new Column("content")), null)
        assert(metadata.getEntityBinding("PropertyLocalizedPageTranslation") != null)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
            session =>
              SessionContentLocale.bind(session, "de")
              val tx = session.beginTransaction()
              val page = new PropertyLocalizedPage()
              page.setId(id)
              session.persist(page)
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", "de")
              row.put("title", "Hallo")
              row.put("content", "**Deutsch**")
              session.persist("PropertyLocalizedPageTranslation", row)
              val english = new util.HashMap[String, Object]()
              english.put("pageId", id)
              english.put("locale", "en")
              english.put("title", "Hello")
              english.put("content", "**English**")
              session.persist("PropertyLocalizedPageTranslation", english)
              tx.commit()
          }
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
            session =>
              SessionContentLocale.bind(session, "de")
              val page = session.find(classOf[PropertyLocalizedPage], id)
              assertEquals(page.getTitle, "Hallo")
              assertEquals(page.getContent, Markdown("**Deutsch**"))
              val queried = session.createQuery(
                "select p from PropertyLocalizedPage p where p.title = :title and p.content = :content",
                classOf[PropertyLocalizedPage]
              ).setParameter("title", "Hallo").setParameter("content", Markdown("**Deutsch**")).getSingleResult
              assert(queried eq page)
          }
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("fr")).openSession()) {
            session =>
              SessionContentLocale.bind(session, "fr")
              val page = session.find(classOf[PropertyLocalizedPage], id)
              assertEquals(page.getTitle, "Hello")
              assertEquals(page.getContent, Markdown("**English**"))
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }

  test("getter edits create and update active-locale rows while null restores fallback") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      inLocale(factory, "de", synchronizer) { session =>
        val page = new PropertyLocalizedPage()
        page.setId(id)
        page.setTitle("Hallo")
        page.setContent(Markdown("**Deutsch**"))
        session.persist(page)
        val found = session.createQuery(
          "from PropertyLocalizedPage p where p.id = :id and p.title = :title",
          classOf[PropertyLocalizedPage]
        ).setParameter("id", id).setParameter("title", "Hallo").getSingleResult
        assert(found eq page)
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Hallo"
      )
      assertEquals(
        scalar(
          dataSource,
          s"select content from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "**Deutsch**"
      )
      inLocale(factory, "en", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getTitle, "Hallo")
        page.setTitle("Hello")
        page.setContent(Markdown("**English**"))
      }
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Guten Tag")
        page.setContent(Markdown("**Neu**"))
      }
      inLocale(factory, "fr", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getTitle, "Hello")
        assertEquals(page.getContent, Markdown("**English**"))
        page.setTitle("Bonjour")
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'fr'"
        ),
        "Bonjour"
      )
      inLocale(factory, "fr", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getContent, Markdown("**English**"))
        page.setTitle(null)
        assertEquals(page.getTitle, null)
      }
      assertEquals(
        scalar(
          dataSource,
          s"select count(*) from property_page_translation where page_id = '$id' and locale = 'fr'"
        ),
        "0"
      )
      inLocale(factory, "fr", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getTitle, "Hello")
        assertEquals(page.getContent, Markdown("**English**"))
      }
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getTitle, "Guten Tag")
        assertEquals(page.getContent, Markdown("**Neu**"))
      }
    }
  }

  test("ALWAYS, COMMIT and MANUAL flush modes preserve translation write timing") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      seedTwoLocales(factory, synchronizer, id)
      def queriedTitle(session: Session): String =
        session.createQuery("select p.title from PropertyLocalizedPage p where p.id = :id", classOf[String])
          .setParameter("id", id).getSingleResult
      inLocale(factory, "de", synchronizer) { session =>
        session.setHibernateFlushMode(FlushMode.COMMIT)
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Nur bei Commit")
        assertEquals(queriedTitle(session), "Hallo")
        assertEquals(page.getTitle, "Nur bei Commit")
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Nur bei Commit"
      )
      inLocale(factory, "de", synchronizer) { session =>
        session.setHibernateFlushMode(FlushMode.MANUAL)
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Ohne Flush")
        assertEquals(queriedTitle(session), "Nur bei Commit")
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Nur bei Commit"
      )
      inLocale(factory, "de", synchronizer) { session =>
        session.setHibernateFlushMode(FlushMode.MANUAL)
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Expliziter Flush")
        session.flush()
        assertEquals(queriedTitle(session), "Expliziter Flush")
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Expliziter Flush"
      )
      inLocale(factory, "de", synchronizer) { session =>
        session.setHibernateFlushMode(FlushMode.ALWAYS)
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Bei Abfrage")
        assertEquals(queriedTitle(session), "Bei Abfrage")
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Bei Abfrage"
      )
    }
  }

  test("getter refresh reloads external values and rejects a dirty editor row") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      seedTwoLocales(factory, synchronizer, id)
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        assertEquals(page.getContent, Markdown("**Deutsch**"))
        session.createNativeMutationQuery(
          "update property_page_translation set title = :title, content = :content where page_id = :id and locale = 'de'"
        ).setParameter("title", "Extern").setParameter("content", "**Extern**")
          .setParameter("id", id).executeUpdate()
        session.refresh(page)
        assertEquals(page.getTitle, "Extern")
        assertEquals(page.getContent, Markdown("**Extern**"))
        page.setTitle("Nach Refresh")
        session.flush()
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Nach Refresh"
      )
      assertEquals(
        scalar(
          dataSource,
          s"select content from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "**Extern**"
      )
      val dirty = intercept[HibernateException] {
        inLocale(factory, "de", synchronizer) { session =>
          val page = session.find(classOf[PropertyLocalizedPage], id)
          val rowId = new util.HashMap[String, Object]()
          rowId.put("pageId", id)
          rowId.put("locale", "de")
          val row = session.find("PropertyLocalizedPageTranslation", rowId)
            .asInstanceOf[util.Map[String, Object]]
          row.put("content", "**Unsent**")
          session.refresh(page)
        }
      }
      assert(dirty.getMessage.contains("modified translation row"))
      assertEquals(
        scalar(
          dataSource,
          s"select content from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "**Extern**"
      )
    }
  }

  test("getter snapshots reset on clear and evict while detached merge stays forbidden") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      seedTwoLocales(factory, synchronizer, id)
      val detached = inLocale(factory, "de", synchronizer) { session =>
        session.find(classOf[PropertyLocalizedPage], id)
      }
      detached.setTitle("Detached")
      for locale <- Seq("de", "en") do
        val error = intercept[HibernateException] {
          inLocale(factory, locale, synchronizer)(_.merge(detached))
        }
        assert(error.getMessage.contains("Cannot merge an unmanaged localized entity"))
      val replication = intercept[HibernateException] {
        inLocale(factory, "en", synchronizer)(_.replicate(detached, ReplicationMode.OVERWRITE))
      }
      assert(replication.getMessage.contains("Cannot replicate a localized entity"))
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Hallo"
      )
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'en'"
        ),
        "Hello"
      )
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Vor Clear")
        session.flush()
        session.clear()
        page.setTitle("Detached nach Clear")
        val deleted = session.createNativeMutationQuery(
          "delete from property_page_translation where page_id = :id and locale = 'de'"
        ).setParameter("id", id).executeUpdate()
        assertEquals(deleted, 1)
        session.load(page, id)
        assertEquals(page.getTitle, "Hello")
        session.flush()
      }
      assertEquals(
        scalar(
          dataSource,
          s"select count(*) from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "0"
      )
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        page.setTitle("Vor Evict")
        session.flush()
        session.evict(page)
        page.setTitle("Detached nach Evict")
        val deleted = session.createNativeMutationQuery(
          "delete from property_page_translation where page_id = :id and locale = 'de'"
        ).setParameter("id", id).executeUpdate()
        assertEquals(deleted, 1)
        session.load(page, id)
        assertEquals(page.getTitle, "Hello")
        session.flush()
      }
      assertEquals(
        scalar(
          dataSource,
          s"select count(*) from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "0"
      )
    }
  }

  test("getter parent deletion evicts managed translation rows before database cascade") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      seedTwoLocales(factory, synchronizer, id)
      inLocale(factory, "de", synchronizer) { session =>
        val page = session.find(classOf[PropertyLocalizedPage], id)
        val germanId = new util.HashMap[String, Object]()
        germanId.put("pageId", id)
        germanId.put("locale", "de")
        val englishId = new util.HashMap[String, Object]()
        englishId.put("pageId", id)
        englishId.put("locale", "en")
        assert(session.find("PropertyLocalizedPageTranslation", germanId) != null)
        assert(session.find("PropertyLocalizedPageTranslation", englishId) != null)
        session.remove(page)
        session.flush()
        assertEquals(session.find("PropertyLocalizedPageTranslation", germanId), null)
        assertEquals(session.find("PropertyLocalizedPageTranslation", englishId), null)
      }
      assertEquals(
        scalar(
          dataSource,
          s"select count(*) from property_page_translation where page_id = '$id'"
        ),
        "0"
      )
    }
  }

  test("getter edits reject a stale write to one locale without blocking another locale") {
    withGetterMapping { (dataSource, factory, synchronizer) =>
      val id = util.UUID.randomUUID()
      seedTwoLocales(factory, synchronizer, id)
      Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
        first =>
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
            second =>
              synchronizer.bind(first, "de")
              synchronizer.bind(second, "de")
              val firstTransaction = first.beginTransaction()
              val secondTransaction = second.beginTransaction()
              try
                val firstPage = first.find(classOf[PropertyLocalizedPage], id)
                val secondPage = second.find(classOf[PropertyLocalizedPage], id)
                firstPage.setTitle("Session Eins")
                secondPage.setTitle("Session Zwei")
                firstTransaction.commit()
                val stale = intercept[RuntimeException](secondTransaction.commit())
                val causes = Iterator.iterate[Throwable](stale)(_.getCause).takeWhile(_ != null)
                assert(causes.exists(_.isInstanceOf[StaleObjectStateException]))
              finally
                if firstTransaction.isActive then firstTransaction.rollback()
                if secondTransaction.isActive then secondTransaction.rollback()
          }
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Session Eins"
      )
      Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
        german =>
          Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("en")).openSession()) {
            english =>
              synchronizer.bind(german, "de")
              synchronizer.bind(english, "en")
              val germanTransaction = german.beginTransaction()
              val englishTransaction = english.beginTransaction()
              try
                german.find(classOf[PropertyLocalizedPage], id).setTitle("Deutsch parallel")
                english.find(classOf[PropertyLocalizedPage], id).setTitle("English parallel")
                germanTransaction.commit()
                englishTransaction.commit()
              finally
                if germanTransaction.isActive then germanTransaction.rollback()
                if englishTransaction.isActive then englishTransaction.rollback()
          }
      }
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'de'"
        ),
        "Deutsch parallel"
      )
      assertEquals(
        scalar(
          dataSource,
          s"select title from property_page_translation where page_id = '$id' and locale = 'en'"
        ),
        "English parallel"
      )
    }
  }

  test("getter query cache separates locales and invalidates changed translation rows") {
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
          .addAnnotatedClassName(classOf[PropertyLocalizedPage].getName)
          .buildMetadata()
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          factory.inTransaction { session =>
            val page = new PropertyLocalizedPage()
            page.setId(id)
            session.persist(page)
            for (locale, title) <- Seq(("de", "Hallo"), ("en", "Hello")) do
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", locale)
              row.put("title", title)
              session.persist("PropertyLocalizedPageTranslation", row)
          }
          factory.getStatistics.clear()
          assertEquals(cachedGetterTitle(factory, "de", id), "Hallo")
          assertEquals(cachedGetterTitle(factory, "en", id), "Hello")
          assertEquals(cachedGetterTitle(factory, "fr", id), "Hello")
          assertEquals(factory.getStatistics.getQueryCachePutCount, 3L)
          assertEquals(cachedGetterTitle(factory, "de", id), "Hallo")
          assertEquals(cachedGetterTitle(factory, "en", id), "Hello")
          assertEquals(cachedGetterTitle(factory, "fr", id), "Hello")
          assertEquals(factory.getStatistics.getQueryCacheHitCount, 3L)
          assertEquals(cachedGetterEntityTitle(factory, "de", id), "Hallo")
          assertEquals(cachedGetterEntityTitle(factory, "en", id), "Hello")
          assertEquals(cachedGetterEntityTitle(factory, "de", id), "Hallo")
          assertEquals(cachedGetterEntityTitle(factory, "en", id), "Hello")
          factory.inTransaction { session =>
            val rowId = new util.HashMap[String, Object]()
            rowId.put("pageId", id)
            rowId.put("locale", "en")
            val row = session.find("PropertyLocalizedPageTranslation", rowId)
              .asInstanceOf[util.Map[String, Object]]
            row.put("title", "Updated")
          }
          assertEquals(cachedGetterTitle(factory, "en", id), "Updated")
          assertEquals(cachedGetterTitle(factory, "fr", id), "Updated")
          assertEquals(cachedGetterTitle(factory, "de", id), "Hallo")
          assertEquals(cachedGetterEntityTitle(factory, "en", id), "Updated")
          assertEquals(cachedGetterEntityTitle(factory, "de", id), "Hallo")
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
