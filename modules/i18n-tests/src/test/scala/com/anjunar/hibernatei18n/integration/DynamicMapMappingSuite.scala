package com.anjunar.hibernatei18n.integration

import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.mapping.Column
import org.hibernate.StaleObjectStateException

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util
import scala.util.Using

/** Checks whether Hibernate's dynamic Map entity can represent a generated internal model. */
class DynamicMapMappingSuite extends TestPostgres:
  test("Hibernate mapping XML contributes a classless Map entity with schema and CRUD") {
    withDatabase { dataSource =>
      val registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        val mapping =
          s"""<hibernate-mapping>
            |  <class entity-name="DynamicTranslation" table="dynamic_translation">
            |    <composite-id>
            |      <key-property name="pageId" column="page_id" type="java.util.UUID"/>
            |      <key-property name="locale" column="locale" type="string"/>
            |    </composite-id>
            |    <version name="rowVersion" column="row_version" type="long"/>
            |    <property name="title" column="title" type="string"/>
            |  </class>
            |</hibernate-mapping>""".stripMargin
        val metadata = new MetadataSources(registry)
          .addAnnotatedClass(classOf[FormulaPage])
          .addInputStream(new ByteArrayInputStream(mapping.getBytes(StandardCharsets.UTF_8)))
          .buildMetadata()
        val binding = metadata.getEntityBinding("DynamicTranslation")
        assert(binding != null)
        assertEquals(binding.getTable.getName, "dynamic_translation")
        assertEquals(binding.getTable.getPrimaryKey.getColumns.size(), 2)
        assert(binding.getTable.getColumn(new Column("row_version")) != null)
        val parentTable = metadata.getEntityBinding(classOf[FormulaPage].getName).getTable
        val pageIdColumn = binding.getTable.getColumn(new Column("page_id"))
        val foreignKey = binding.getTable.createForeignKey(
          "fk_dynamic_translation_page",
          util.List.of(pageIdColumn),
          classOf[FormulaPage].getName,
          null,
          null,
          null
        )
        foreignKey.setReferencedTable(parentTable)
        foreignKey.setOnDeleteAction(OnDeleteAction.CASCADE)
        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          var translationId: Object = null
          factory.inTransaction { session =>
            val page = new FormulaPage()
            page.id = id
            page.slug = "dynamic"
            session.persist(page)
            val row = new util.HashMap[String, Object]()
            row.put("pageId", id)
            row.put("locale", "de")
            row.put("title", "Hallo")
            session.persist("DynamicTranslation", row)
            translationId = session.getIdentifier(row)
          }
          assert(translationId != null)
          assertEquals(
            scalar(dataSource, s"select row_version from dynamic_translation where page_id = '$id' and locale = 'de'"),
            "0"
          )
          val compositeId = translationId.asInstanceOf[util.Map[String, Object]]
          assertEquals(compositeId.get("pageId"), id)
          assertEquals(compositeId.get("locale"), "de")
          factory.inTransaction { session =>
            val loaded = session.find("DynamicTranslation", translationId)
              .asInstanceOf[util.Map[String, Object]]
            assert(loaded != null)
            assertEquals(loaded.get("pageId"), id)
            assertEquals(loaded.get("locale"), "de")
            assertEquals(loaded.get("title"), "Hallo")
            loaded.put("title", "Guten Tag")
          }
          assertEquals(
            scalar(dataSource, s"select title from dynamic_translation where page_id = '$id' and locale = 'de'"),
            "Guten Tag"
          )
          assertEquals(
            scalar(dataSource, s"select row_version from dynamic_translation where page_id = '$id' and locale = 'de'"),
            "1"
          )
          Using.resource(factory.openSession()) { first =>
            Using.resource(factory.openSession()) { second =>
              val firstTransaction = first.beginTransaction()
              val secondTransaction = second.beginTransaction()
              try
                val firstRow = first.find("DynamicTranslation", translationId)
                  .asInstanceOf[util.Map[String, Object]]
                val secondRow = second.find("DynamicTranslation", translationId)
                  .asInstanceOf[util.Map[String, Object]]
                firstRow.put("title", "Erster")
                secondRow.put("title", "Zweiter")
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
            scalar(dataSource, s"select title from dynamic_translation where page_id = '$id' and locale = 'de'"),
            "Erster"
          )
          assertEquals(
            scalar(dataSource, s"select row_version from dynamic_translation where page_id = '$id' and locale = 'de'"),
            "2"
          )
          factory.inTransaction { session =>
            session.remove(session.find(classOf[FormulaPage], id))
          }
          assertEquals(scalar(dataSource, s"select count(*) from dynamic_translation where page_id = '$id'"), "0")
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
