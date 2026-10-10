package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard
import org.hibernate.{HibernateException, ReplicationMode, Session, SessionFactory, StaleObjectStateException}
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.AdditionalMappingContributor
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.mapping.Column

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Isolates the experimental mapping from the production guard for one bootstrap proof. */
class AnnotatedBootstrapExperimentSuite extends TestPostgres:
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

  test("early contributor derives formula and classless rows from annotated ordinary fields") {
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
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .applySetting("hibernate.generate_statistics", "true")
        .build()
      try
        val sources = new MetadataSources(registry).addAnnotatedClassName(classOf[LocalizedPage].getName)
        val metadata = sources.buildMetadata()
        assertEquals(sources.getMappingXmlBindings.size(), 1)
        val parent = metadata.getEntityBinding(classOf[LocalizedPage].getName)
        val translation = metadata.getEntityBinding("LocalizedPageTranslation")
        assert(parent.hasProperty("title"))
        assert(parent.hasProperty("content"))
        assertEquals(parent.getTable.getName, "page")
        assertEquals(parent.getTable.getColumn(new Column("title")), null)
        assertEquals(parent.getTable.getColumn(new Column("content")), null)
        assertEquals(parent.getTable.getColumn(new Column("row_version")), null)
        assertEquals(translation.getTable.getName, "page_translation")
        assertEquals(translation.getTable.getPrimaryKey.getColumns.size(), 2)
        assert(translation.isVersioned)
        assert(translation.getTable.getColumn(new Column("row_version")) != null)
        assert(parent.getSynchronizedTables.contains("page_translation"))
        assertEquals(translation.getTable.getForeignKeyCollection.size(), 1)
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
          listenerRegistry.appendListeners(EventType.CLEAR, synchronizer)
          listenerRegistry.appendListeners(EventType.EVICT, synchronizer)
          listenerRegistry.appendListeners(EventType.REFRESH, synchronizer)
          listenerRegistry.prependListeners(EventType.MERGE, synchronizer)
          listenerRegistry.prependListeners(EventType.REPLICATE, synchronizer)
          listenerRegistry.appendListeners(EventType.POST_LOAD, synchronizer)
          listenerRegistry.appendListeners(
            EventType.PRE_DELETE,
            new TranslationCascadeEvictor[LocalizedPage](classOf[LocalizedPage], "LocalizedPageTranslation", _.id)
          )
          val id = util.UUID.randomUUID()
          val defaultOnlyId = util.UUID.randomUUID()
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = new LocalizedPage()
            page.id = id
            page.slug = "annotated"
            session.persist(page)
            for (locale, title, content) <- List(("de", "Hallo", "**Deutsch**"), ("en", "Hello", "**English**")) do
              val row = new util.HashMap[String, Object]()
              row.put("pageId", id)
              row.put("locale", locale)
              row.put("title", title)
              row.put("content", content)
              session.persist("LocalizedPageTranslation", row)
            val defaultOnly = new LocalizedPage()
            defaultOnly.id = defaultOnlyId
            defaultOnly.slug = "default-only"
            session.persist(defaultOnly)
            val defaultRow = new util.HashMap[String, Object]()
            defaultRow.put("pageId", defaultOnlyId)
            defaultRow.put("locale", "de")
            defaultRow.put("title", "Nur Deutsch")
            defaultRow.put("content", "**Standard**")
            session.persist("LocalizedPageTranslation", defaultRow)
          }
          assertEquals(
            scalar(dataSource, s"select row_version from page_translation where page_id = '$id' and locale = 'de'"),
            "0"
          )
          inLocaleTransaction(factory, "de-DE", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, Markdown("**Deutsch**"))
            session.flush()
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'de-DE'"),
            "0"
          )
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**English**"))
            val queried = session.createQuery(
              "select p from LocalizedPage p where p.title = :title and p.content = :content",
              classOf[LocalizedPage]
            )
              .setParameter("title", "Hello")
              .setParameter("content", Markdown("**English**"))
              .getSingleResult
            assert(queried eq page)
            session.flush()
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'fr'"),
            "0"
          )
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], defaultOnlyId)
            assertEquals(page.title, "Nur Deutsch")
            assertEquals(page.content, Markdown("**Standard**"))
            session.flush()
          }
          assertEquals(
            scalar(
              dataSource,
              s"select count(*) from page_translation where page_id = '$defaultOnlyId' and locale = 'fr'"
            ),
            "0"
          )
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], defaultOnlyId)
            page.title = null
            session.flush()
            session.refresh(page)
            assertEquals(page.title, "Nur Deutsch")
          }
          assertEquals(
            scalar(
              dataSource,
              s"select count(*) from page_translation where page_id = '$defaultOnlyId' and locale = 'fr'"
            ),
            "0"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            intercept[IllegalStateException](synchronizer.bind(session, "en"))
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, Markdown("**Deutsch**"))
            val found =
              session.createQuery("select p from LocalizedPage p where p.title = :title", classOf[LocalizedPage])
                .setParameter("title", "Hallo")
                .getSingleResult
            assert(found eq page)
            val byContent =
              session.createQuery("select p from LocalizedPage p where p.content = :content", classOf[LocalizedPage])
                .setParameter("content", Markdown("**Deutsch**"))
                .getSingleResult
            assert(byContent eq page)
            page.title = "Guten Tag"
            page.content = Markdown("**Aktuell**")
            val changed = session.createQuery(
              "select p from LocalizedPage p where p.title = :title and p.content = :content",
              classOf[LocalizedPage]
            )
              .setParameter("title", "Guten Tag")
              .setParameter("content", Markdown("**Aktuell**"))
              .getSingleResult
            assert(changed eq page)
            session.flush()
            val translationId = new util.HashMap[String, Object]()
            translationId.put("pageId", id)
            translationId.put("locale", "de")
            val editorRow = session.find("LocalizedPageTranslation", translationId)
              .asInstanceOf[util.Map[String, Object]]
            editorRow.put("title", "Redaktion")
            session.flush()
            assertEquals(page.title, "Guten Tag")
          }
          inLocaleTransaction(factory, "en", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**English**"))
          }
          assertEquals(scalar(dataSource, s"select count(*) from page_translation where page_id = '$id'"), "2")
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          assertEquals(
            scalar(dataSource, s"select content from page_translation where page_id = '$id' and locale = 'de'"),
            "**Aktuell**"
          )
          val versionBeforeConcurrentWrite = scalar(
            dataSource,
            s"select row_version from page_translation where page_id = '$id' and locale = 'de'"
          ).toLong
          Using.resource(
            factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()
          ) { first =>
            Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
              second =>
                synchronizer.bind(first, "de")
                synchronizer.bind(second, "de")
                val firstTransaction = first.beginTransaction()
                val secondTransaction = second.beginTransaction()
                try
                  val firstPage = first.find(classOf[LocalizedPage], id)
                  val secondPage = second.find(classOf[LocalizedPage], id)
                  firstPage.title = "Session Eins"
                  secondPage.title = "Session Zwei"
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
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Session Eins"
          )
          assertEquals(
            scalar(
              dataSource,
              s"select row_version from page_translation where page_id = '$id' and locale = 'de'"
            ).toLong,
            versionBeforeConcurrentWrite + 1
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).title = "Redaktion"
          }
          Using.resource(
            factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()
          ) { first =>
            Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()) {
              second =>
                synchronizer.bind(first, "de")
                synchronizer.bind(second, "de")
                val firstTransaction = first.beginTransaction()
                val secondTransaction = second.beginTransaction()
                try
                  val refreshedPage = first.find(classOf[LocalizedPage], id)
                  first.refresh(refreshedPage)
                  second.find(classOf[LocalizedPage], id).title = "Nach Refresh fremd"
                  secondTransaction.commit()
                  refreshedPage.title = "Nach Refresh veraltet"
                  val stale = intercept[RuntimeException](firstTransaction.commit())
                  val causes = Iterator.iterate[Throwable](stale)(_.getCause).takeWhile(_ != null)
                  assert(causes.exists(_.isInstanceOf[StaleObjectStateException]))
                finally
                  if firstTransaction.isActive then firstTransaction.rollback()
                  if secondTransaction.isActive then secondTransaction.rollback()
            }
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Nach Refresh fremd"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).title = "Redaktion"
          }
          Using.resource(
            factory.withOptions().statementInspector(new FixedLocaleSqlInspector("de")).openSession()
          ) { german =>
            Using.resource(factory.withOptions().statementInspector(new FixedLocaleSqlInspector("en")).openSession()) {
              english =>
                synchronizer.bind(german, "de")
                synchronizer.bind(english, "en")
                val germanTransaction = german.beginTransaction()
                val englishTransaction = english.beginTransaction()
                try
                  german.find(classOf[LocalizedPage], id).title = "Deutsch parallel"
                  english.find(classOf[LocalizedPage], id).title = "English parallel"
                  germanTransaction.commit()
                  englishTransaction.commit()
                finally
                  if germanTransaction.isActive then germanTransaction.rollback()
                  if englishTransaction.isActive then englishTransaction.rollback()
            }
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Deutsch parallel"
          )
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'en'"),
            "English parallel"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).title = "Redaktion"
          }
          inLocaleTransaction(factory, "en", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).title = "Hello"
          }
          val fieldConflict = intercept[HibernateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              val page = session.find(classOf[LocalizedPage], id)
              val translationId = new util.HashMap[String, Object]()
              translationId.put("pageId", id)
              translationId.put("locale", "de")
              val row = session.find("LocalizedPageTranslation", translationId)
                .asInstanceOf[util.Map[String, Object]]
              page.title = "Domain-Titel"
              row.put("title", "Editor-Titel")
              session.flush()
            }
          }
          assert(fieldConflict.getMessage.contains("Conflicting translation edit"))
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          intercept[IllegalStateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              val page = session.find(classOf[LocalizedPage], id)
              val translationId = new util.HashMap[String, Object]()
              translationId.put("pageId", id)
              translationId.put("locale", "de")
              val row = session.find("LocalizedPageTranslation", translationId)
                .asInstanceOf[util.Map[String, Object]]
              page.title = "Domain-Titel"
              row.put("content", "**Editor-Content**")
              session.flush()
              val title = session.createNativeQuery(
                "select title from page_translation where page_id = :id and locale = 'de'",
                classOf[String]
              )
                .setParameter("id", id)
                .getSingleResult
              val content = session.createNativeQuery(
                "select content from page_translation where page_id = :id and locale = 'de'",
                classOf[String]
              )
                .setParameter("id", id)
                .getSingleResult
              assertEquals(title, "Domain-Titel")
              assertEquals(content, "**Editor-Content**")
              throw new IllegalStateException("disjoint edit probe rollback")
            }
          }
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            assertEquals(session.find(classOf[LocalizedPage], id).title, "Redaktion")
          }
          intercept[IllegalStateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              val page = session.find(classOf[LocalizedPage], id)
              page.title = "Vorher"
              session.flush()
              session.createNativeMutationQuery(
                "update page_translation set title = :title, content = :content where page_id = :id and locale = 'de'"
              )
                .setParameter("title", "Redaktion")
                .setParameter("content", "**Extern**")
                .setParameter("id", id)
                .executeUpdate()
              session.refresh(page)
              assertEquals(page.title, "Redaktion")
              assertEquals(page.content, Markdown("**Extern**"))
              val updatesBefore = factory.getStatistics.getEntityUpdateCount
              session.flush()
              assertEquals(factory.getStatistics.getEntityUpdateCount, updatesBefore)
              page.title = "Nachher"
              session.flush()
              val content = session.createNativeQuery(
                "select content from page_translation where page_id = :id and locale = 'de'",
                classOf[String]
              )
                .setParameter("id", id)
                .getSingleResult
              assertEquals(content, "**Extern**")
              throw new IllegalStateException("refresh probe rollback")
            }
          }
          val dirtyRefresh = intercept[HibernateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              val page = session.find(classOf[LocalizedPage], id)
              val translationId = new util.HashMap[String, Object]()
              translationId.put("pageId", id)
              translationId.put("locale", "de")
              val row = session.find("LocalizedPageTranslation", translationId)
                .asInstanceOf[util.Map[String, Object]]
              row.put("content", "**Unsent**")
              session.refresh(page)
            }
          }
          assert(dirtyRefresh.getMessage.contains("modified translation row"))
          assertEquals(
            scalar(dataSource, s"select content from page_translation where page_id = '$id' and locale = 'de'"),
            "**Aktuell**"
          )
          val detached = inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id)
          }
          detached.title = "Zusammengeführt"
          val detachedMerge = intercept[HibernateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              session.merge(detached)
            }
          }
          assert(detachedMerge.getMessage.contains("Cannot merge an unmanaged localized entity"))
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val managed = session.find(classOf[LocalizedPage], id)
            assert(session.merge(managed) eq managed)
          }
          val crossLocaleMerge = intercept[HibernateException] {
            inLocaleTransaction(factory, "en", synchronizer) { session =>
              session.merge(detached)
            }
          }
          assert(crossLocaleMerge.getMessage.contains("Cannot merge an unmanaged localized entity"))
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'en'"),
            "Hello"
          )
          val crossLocaleReplication = intercept[HibernateException] {
            inLocaleTransaction(factory, "en", synchronizer) { session =>
              session.replicate(detached, ReplicationMode.OVERWRITE)
            }
          }
          assert(crossLocaleReplication.getMessage.contains("Cannot replicate a localized entity"))
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'en'"),
            "Hello"
          )
          val unknownOrigin = new LocalizedPage()
          unknownOrigin.id = id
          unknownOrigin.slug = "unknown-origin"
          unknownOrigin.title = "Unknown"
          val unknownMerge = intercept[HibernateException] {
            inLocaleTransaction(factory, "de", synchronizer)(_.merge(unknownOrigin))
          }
          assert(unknownMerge.getMessage.contains("Cannot merge an unmanaged localized entity"))
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          val staleFrench = inLocaleTransaction(factory, "fr", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id)
          }
          staleFrench.title = "Un titre"
          // Without the merge guard, the obsolete inherited English content becomes an explicit French value.
          val staleFallbackMerge = intercept[HibernateException] {
            inLocaleTransaction(factory, "fr", synchronizer) { session =>
              session.createNativeMutationQuery(
                "update page_translation set content = :content where page_id = :id and locale = 'en'"
              )
                .setParameter("content", "**Newer English**")
                .setParameter("id", id)
                .executeUpdate()
              session.merge(staleFrench)
            }
          }
          assert(staleFallbackMerge.getMessage.contains("Cannot merge an unmanaged localized entity"))
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'fr'"),
            "0"
          )
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**English**"))
            page.title = "Bonjour"
            val found =
              session.createQuery("select p from LocalizedPage p where p.title = :title", classOf[LocalizedPage])
                .setParameter("title", "Bonjour")
                .getSingleResult
            assert(found eq page)
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'fr'"),
            "Bonjour"
          )
          assertEquals(
            scalar(dataSource, s"select content from page_translation where page_id = '$id' and locale = 'fr'"),
            null
          )
          inLocaleTransaction(factory, "en", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).content = Markdown("**English updated**")
          }
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            assertEquals(page.title, "Bonjour")
            assertEquals(page.content, Markdown("**English updated**"))
          }
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            page.title = null
            session.flush()
            session.refresh(page)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**English updated**"))
            session.flush()
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'fr'"),
            "0"
          )
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            assertEquals(session.find(classOf[LocalizedPage], id).title, "Hello")
          }
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).title = "Bonjour"
          }
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            session.find(classOf[LocalizedPage], id).content = Markdown("**Français**")
          }
          inLocaleTransaction(factory, "fr", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            page.title = null
            session.flush()
            session.refresh(page)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**Français**"))
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'fr'"),
            null
          )
          assertEquals(
            scalar(dataSource, s"select content from page_translation where page_id = '$id' and locale = 'fr'"),
            "**Français**"
          )
          val insertedId = util.UUID.randomUUID()
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = new LocalizedPage()
            page.id = insertedId
            page.slug = "new"
            page.title = "Neu"
            session.persist(page)
            val first = session.createQuery(
              "select p from LocalizedPage p where p.id = :id and p.title = :title",
              classOf[LocalizedPage]
            ).setParameter("id", insertedId).setParameter("title", "Neu").getSingleResult
            assert(first eq page)
            page.title = "Erneut neu"
            val second = session.createQuery(
              "select p from LocalizedPage p where p.id = :id and p.title = :title",
              classOf[LocalizedPage]
            ).setParameter("id", insertedId).setParameter("title", "Erneut neu").getSingleResult
            assert(second eq page)
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$insertedId' and locale = 'de'"),
            "Erneut neu"
          )
          intercept[IllegalStateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              session.find(classOf[LocalizedPage], id).title = "Zurückgerollt"
              session.flush()
              throw new IllegalStateException("rollback probe")
            }
          }
          assertEquals(
            scalar(dataSource, s"select title from page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            page.title = "Vor Clear"
            session.flush()
            session.clear()
            page.title = "Detached nach Clear"
            val deleted =
              session.createNativeMutationQuery("delete from page_translation where page_id = :id and locale = 'de'")
                .setParameter("id", id)
                .executeUpdate()
            assertEquals(deleted, 1)
            session.load(page, id)
            assertEquals(page.title, "Hello")
            session.flush()
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'de'"),
            "0"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            page.title = "Vor Evict"
            session.flush()
            page.title = "Noch vor Evict"
            session.flush()
            val activeTitle = session.createNativeQuery(
              "select title from page_translation where page_id = :id and locale = 'de'",
              classOf[String]
            ).setParameter("id", id).getSingleResult
            assertEquals(activeTitle, "Noch vor Evict")
            session.evict(page)
            page.title = "Detached nach Evict"
            val deleted =
              session.createNativeMutationQuery("delete from page_translation where page_id = :id and locale = 'de'")
                .setParameter("id", id)
                .executeUpdate()
            assertEquals(deleted, 1)
            session.load(page, id)
            assertEquals(page.title, "Hello")
            session.flush()
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$id' and locale = 'de'"),
            "0"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = session.find(classOf[LocalizedPage], id)
            val englishId = new util.HashMap[String, Object]()
            englishId.put("pageId", id)
            englishId.put("locale", "en")
            val english = session.find("LocalizedPageTranslation", englishId)
              .asInstanceOf[util.Map[String, Object]]
            english.put("title", "Soon deleted")
            session.remove(page)
            session.remove(session.find(classOf[LocalizedPage], defaultOnlyId))
            session.flush()
            val germanId = new util.HashMap[String, Object]()
            germanId.put("pageId", id)
            germanId.put("locale", "de")
            assert(session.find("LocalizedPageTranslation", germanId) == null)
            assert(session.find("LocalizedPageTranslation", englishId) == null)
            session.flush()
            assert(session.find("LocalizedPageTranslation", germanId) == null)
          }
          assertEquals(scalar(dataSource, s"select count(*) from page_translation where page_id = '$id'"), "0")
          assertEquals(
            scalar(dataSource, s"select count(*) from page_translation where page_id = '$defaultOnlyId'"),
            "0"
          )
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
