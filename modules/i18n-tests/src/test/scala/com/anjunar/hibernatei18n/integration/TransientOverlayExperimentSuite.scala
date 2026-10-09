package com.anjunar.hibernatei18n.integration

import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Using

/** Demonstrates why excluding a field at bootstrap does not meet transparent dirty checking. */
class TransientOverlayExperimentSuite extends TestPostgres:
  test("an XML transient overlay excludes the original field from loading and dirty checking") {
    withDatabase { dataSource =>
      val registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        val overlay =
          s"""<entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
             |  <entity class="${classOf[OverlayPage].getName}">
             |    <attributes><transient name="title"/></attributes>
             |  </entity>
             |</entity-mappings>""".stripMargin
        val metadata = new MetadataSources(registry)
          .addAnnotatedClass(classOf[OverlayPage])
          .addInputStream(new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)))
          .buildMetadata()
        val parent = metadata.getEntityBinding(classOf[OverlayPage].getName)
        assert(!parent.hasProperty("title"))
        assert(!parent.getTable.getColumns.stream().anyMatch(_.getName == "title"))

        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = UUID.randomUUID()
          factory.inTransaction { session =>
            val page = new OverlayPage()
            page.id = id
            page.title = "Hallo"
            session.persist(page)
          }
          factory.inTransaction { session =>
            val page = session.find(classOf[OverlayPage], id)
            assertEquals(page.title, null)
            page.title = "Hello"
          }
          factory.inTransaction { session =>
            assertEquals(session.find(classOf[OverlayPage], id).title, null)
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
