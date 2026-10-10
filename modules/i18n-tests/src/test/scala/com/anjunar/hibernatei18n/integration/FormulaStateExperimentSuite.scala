package com.anjunar.hibernatei18n.integration

import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.engine.spi.SessionImplementor
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util
import scala.util.Using

/** Measures what Hibernate actually tracks for a formula-backed translated property. */
class FormulaStateExperimentSuite extends TestPostgres:
  private def inLocaleTransaction[A](
    factory: SessionFactory,
    locale: String,
    synchronizer: FormulaTranslationSynchronizer
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

  test("a formula reads localized state while Hibernate manages translation rows") {
    withDatabase { dataSource =>
      val registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        val overlay =
          s"""<entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
             |  <entity class="${classOf[FormulaPage].getName}">
             |    <synchronize table="formula_page_translation"/>
             |    <attributes>
             |      <basic name="title"><formula>(select t.title from formula_page_translation t where t.page_id = {alias}.id and t.locale = '__hibernate_i18n_locale__')</formula></basic>
             |      <basic name="content"><formula>(select t.content from formula_page_translation t where t.page_id = {alias}.id and t.locale = '__hibernate_i18n_locale__')</formula></basic>
             |    </attributes>
             |  </entity>
             |</entity-mappings>""".stripMargin
        val metadata = new MetadataSources(registry)
          .addAnnotatedClass(classOf[FormulaPage])
          .addAnnotatedClass(classOf[FormulaPageTranslation])
          .addInputStream(new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)))
          .buildMetadata()
        assert(metadata.getEntityBinding(classOf[FormulaPage].getName).hasProperty("title"))
        val translationBinding = metadata.getEntityBinding(classOf[FormulaPageTranslation].getName)
        assertEquals(translationBinding.getTable.getName, "formula_page_translation")
        assert(translationBinding.getTable.getPrimaryKey != null)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val synchronizer = new FormulaTranslationSynchronizer()
          factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
            .prependListeners(EventType.FLUSH, synchronizer)
          factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
            .prependListeners(EventType.AUTO_FLUSH, synchronizer)
          val id = util.UUID.randomUUID()
          factory.inTransaction { session =>
            val page = new FormulaPage()
            page.id = id
            page.slug = "test"
            session.persist(page)
            val deId = new FormulaTranslationId()
            deId.pageId = id
            deId.locale = "de"
            val german = new FormulaPageTranslation()
            german.id = deId
            german.page = page
            german.title = "Hallo"
            german.content = Markdown("**Deutsch**")
            session.persist(german)

            val enId = new FormulaTranslationId()
            enId.pageId = id
            enId.locale = "en"
            val english = new FormulaPageTranslation()
            english.id = enId
            english.page = page
            english.title = "Hello"
            english.content = Markdown("**English**")
            session.persist(english)
          }
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            intercept[IllegalStateException](synchronizer.bind(session, "en"))
            val page = session.find(classOf[FormulaPage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, new Markdown("**Deutsch**"))
            val internal = session.unwrap(classOf[SessionImplementor])
            val entry = internal.getPersistenceContextInternal.getEntry(page)
            val persister = entry.getPersister
            val titleIndex = persister.getPropertyNames.indexOf("title")
            assert(titleIndex >= 0)
            assertEquals(entry.getLoadedState.apply(titleIndex), "Hallo")
            assertEquals(persister.getPropertyUpdateability()(titleIndex), false)
            val queried =
              session.createQuery("select p from FormulaPage p where p.title = :title", classOf[FormulaPage])
                .setParameter("title", "Hallo")
                .getSingleResult
            assert(queried eq page)
            page.title = "Guten Tag"
            val dirty = persister.findDirty(persister.getValues(page), entry.getLoadedState, page, internal)
            assert(dirty == null || !dirty.contains(titleIndex))
            val afterChange =
              session.createQuery("select p from FormulaPage p where p.title = :title", classOf[FormulaPage])
                .setParameter("title", "Guten Tag")
                .getResultList
            assertEquals(afterChange.size(), 1)
            page.content = Markdown("**Aktuell**")
            val byContent =
              session.createQuery("select p from FormulaPage p where p.content = :content", classOf[FormulaPage])
                .setParameter("content", Markdown("**Aktuell**"))
                .getSingleResult
            assert(byContent eq page)
            session.flush()
            assertEquals(page.title, "Guten Tag")
            val translationId = new FormulaTranslationId()
            translationId.pageId = id
            translationId.locale = "de"
            session.find(classOf[FormulaPageTranslation], translationId).title = "Redaktion"
            session.flush()
            assertEquals(page.title, "Guten Tag")
          }
          inLocaleTransaction(factory, "en", synchronizer) { session =>
            assertEquals(session.find(classOf[FormulaPage], id).title, "Hello")
            assertEquals(session.find(classOf[FormulaPage], id).content, new Markdown("**English**"))
          }
          assertEquals(
            scalar(dataSource, s"select title from formula_page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          assertEquals(
            scalar(dataSource, s"select content from formula_page_translation where page_id = '$id' and locale = 'de'"),
            "**Aktuell**"
          )
          assertEquals(
            scalar(dataSource, s"select title from formula_page_translation where page_id = '$id' and locale = 'en'"),
            "Hello"
          )
          assertEquals(
            scalar(dataSource, s"select content from formula_page_translation where page_id = '$id' and locale = 'en'"),
            "**English**"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            assertEquals(session.find(classOf[FormulaPage], id).title, "Redaktion")
          }

          val insertedId = util.UUID.randomUUID()
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = new FormulaPage()
            page.id = insertedId
            page.slug = "new"
            page.title = "Neu"
            session.persist(page)
          }
          assertEquals(
            scalar(
              dataSource,
              s"select title from formula_page_translation where page_id = '$insertedId' and locale = 'de'"
            ),
            "Neu"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            assertEquals(session.find(classOf[FormulaPage], insertedId).title, "Neu")
          }
          intercept[IllegalStateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              val page = session.find(classOf[FormulaPage], id)
              page.title = "Zurückgerollt"
              session.flush()
              throw new IllegalStateException("rollback probe")
            }
          }
          assertEquals(
            scalar(dataSource, s"select title from formula_page_translation where page_id = '$id' and locale = 'de'"),
            "Redaktion"
          )
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.remove(session.find(classOf[FormulaPage], insertedId))
          }
          assertEquals(
            scalar(dataSource, s"select count(*) from formula_page_translation where page_id = '$insertedId'"),
            "0"
          )
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
