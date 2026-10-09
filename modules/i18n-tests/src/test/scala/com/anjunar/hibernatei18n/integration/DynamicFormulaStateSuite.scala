package com.anjunar.hibernatei18n.integration

import org.hibernate.{Session, SessionFactory}
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.mapping.Column

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.{HashMap, UUID}
import scala.util.Using

/** End-to-end probe without a handwritten translation entity class. */
class DynamicFormulaStateSuite extends TestPostgres:
  private def inLocaleTransaction[A](factory: SessionFactory, locale: String, synchronizer: MapTranslationSynchronizer[FormulaPage])(body: Session => A): A =
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

  test("classless translation mapping preserves ordinary domain fields across locales and flushes") {
    withDatabase { dataSource =>
      val registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.cache.use_second_level_cache", "false")
        .applySetting("hibernate.cache.use_query_cache", "false")
        .build()
      try
        val mapping =
          s"""<entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
             |  <entity class="${classOf[FormulaPage].getName}">
             |    <synchronize table="formula_page_translation"/>
             |    <attributes>
             |      <basic name="title"><formula>(select t.title from formula_page_translation t where t.page_id = {alias}.id and t.locale = '__hibernate_i18n_locale__')</formula></basic>
             |      <basic name="content"><formula>(select t.content from formula_page_translation t where t.page_id = {alias}.id and t.locale = '__hibernate_i18n_locale__')</formula></basic>
             |    </attributes>
             |  </entity>
             |  <entity name="DynamicFormulaTranslation" metadata-complete="true">
             |    <table name="formula_page_translation"/>
             |    <attributes>
             |      <id name="pageId"><column name="page_id"/><target>java.util.UUID</target></id>
             |      <id name="locale"><column name="locale"/><target>java.lang.String</target></id>
             |      <basic name="title"><column name="title"/><target>java.lang.String</target></basic>
             |      <basic name="content"><column name="content"/><target>java.lang.String</target></basic>
             |    </attributes>
             |  </entity>
             |</entity-mappings>""".stripMargin
        val metadata = new MetadataSources(registry)
          .addAnnotatedClass(classOf[FormulaPage])
          .addInputStream(new ByteArrayInputStream(mapping.getBytes(StandardCharsets.UTF_8)))
          .buildMetadata()
        val binding = metadata.getEntityBinding("DynamicFormulaTranslation")
        assertEquals(binding.getTable.getPrimaryKey.getColumns.size(), 2)
        val parentTable = metadata.getEntityBinding(classOf[FormulaPage].getName).getTable
        val pageIdColumn = binding.getTable.getColumn(new Column("page_id"))
        val foreignKey = binding.getTable.createForeignKey(
          "fk_formula_translation_page", java.util.List.of(pageIdColumn),
          classOf[FormulaPage].getName, null, null, null
        )
        foreignKey.setReferencedTable(parentTable)
        foreignKey.setOnDeleteAction(OnDeleteAction.CASCADE)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val synchronizer = new MapTranslationSynchronizer[FormulaPage](
            classOf[FormulaPage], "DynamicFormulaTranslation", _.id, _.title, _.content
          )
          val listenerRegistry = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
            .getService(classOf[EventListenerRegistry])
          listenerRegistry.prependListeners(EventType.FLUSH, synchronizer)
          listenerRegistry.prependListeners(EventType.AUTO_FLUSH, synchronizer)
          listenerRegistry.appendListeners(EventType.REFRESH, synchronizer)
          listenerRegistry.prependListeners(EventType.MERGE, synchronizer)
          listenerRegistry.appendListeners(EventType.POST_LOAD, synchronizer)
          val id = UUID.randomUUID()
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = new FormulaPage()
            page.id = id
            page.slug = "dynamic-formula"
            session.persist(page)
            for (locale, title, content) <- List(("de", "Hallo", "**Deutsch**"), ("en", "Hello", "**English**")) do
              val translation = new HashMap[String, Object]()
              translation.put("pageId", id)
              translation.put("locale", locale)
              translation.put("title", title)
              translation.put("content", content)
              session.persist("DynamicFormulaTranslation", translation)
          }
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            intercept[IllegalStateException](synchronizer.bind(session, "en"))
            val page = session.find(classOf[FormulaPage], id)
            assertEquals(page.title, "Hallo")
            assertEquals(page.content, Markdown("**Deutsch**"))
            page.title = "Guten Tag"
            page.content = Markdown("**Aktuell**")
            val found = session.createQuery("select p from FormulaPage p where p.title = :title and p.content = :content", classOf[FormulaPage])
              .setParameter("title", "Guten Tag")
              .setParameter("content", Markdown("**Aktuell**"))
              .getSingleResult
            assert(found eq page)
            session.flush()
            val translationId = new HashMap[String, Object]()
            translationId.put("pageId", id)
            translationId.put("locale", "de")
            val translation = session.find("DynamicFormulaTranslation", translationId)
              .asInstanceOf[java.util.Map[String, Object]]
            translation.put("title", "Redaktion")
            session.flush()
            assertEquals(page.title, "Guten Tag")
          }
          inLocaleTransaction(factory, "en", synchronizer) { session =>
            val page = session.find(classOf[FormulaPage], id)
            assertEquals(page.title, "Hello")
            assertEquals(page.content, Markdown("**English**"))
          }
          assertEquals(scalar(dataSource, s"select title from formula_page_translation where page_id = '$id' and locale = 'de'"), "Redaktion")
          assertEquals(scalar(dataSource, s"select content from formula_page_translation where page_id = '$id' and locale = 'de'"), "**Aktuell**")
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            assertEquals(session.find(classOf[FormulaPage], id).title, "Redaktion")
          }
          val insertedId = UUID.randomUUID()
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            val page = new FormulaPage()
            page.id = insertedId
            page.slug = "new"
            page.title = "Neu"
            session.persist(page)
          }
          assertEquals(scalar(dataSource, s"select title from formula_page_translation where page_id = '$insertedId' and locale = 'de'"), "Neu")
          intercept[IllegalStateException] {
            inLocaleTransaction(factory, "de", synchronizer) { session =>
              session.find(classOf[FormulaPage], id).title = "Zurückgerollt"
              session.flush()
              throw new IllegalStateException("rollback probe")
            }
          }
          assertEquals(scalar(dataSource, s"select title from formula_page_translation where page_id = '$id' and locale = 'de'"), "Redaktion")
          inLocaleTransaction(factory, "de", synchronizer) { session =>
            session.remove(session.find(classOf[FormulaPage], insertedId))
          }
          assertEquals(scalar(dataSource, s"select count(*) from formula_page_translation where page_id = '$insertedId'"), "0")
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
