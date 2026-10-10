package com.anjunar.hibernatei18n.integration

import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.util
import scala.util.Using

/** Verifies the Java/Scala test toolchain and real Hibernate/PostgreSQL lifecycle. */
class HibernateBootstrapSuite extends TestPostgres:
  test("Hibernate exposes metadata and persists, loads, updates and deletes a plain entity") {
    withDatabase { dataSource =>
      val registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", dataSource)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build()
      try
        val metadata = new MetadataSources(registry)
          .addAnnotatedClass(classOf[BootstrapPage])
          .buildMetadata()
        val binding = metadata.getEntityBinding(classOf[BootstrapPage].getName)
        assertEquals(binding.getTable.getName, "bootstrap_page")

        Using.resource(metadata.buildSessionFactory()) { factory =>
          val id = util.UUID.randomUUID()
          factory.inTransaction { session =>
            val page = new BootstrapPage()
            page.id = id
            page.slug = "hello"
            session.persist(page)
          }
          factory.inTransaction { session =>
            val page = session.find(classOf[BootstrapPage], id)
            assertEquals(page.slug, "hello")
            assert(page eq session.find(classOf[BootstrapPage], id))
            page.slug = "updated"
          }
          factory.inTransaction { session =>
            val page = session.find(classOf[BootstrapPage], id)
            assertEquals(page.slug, "updated")
            session.remove(page)
          }
          factory.inTransaction { session =>
            assert(session.find(classOf[BootstrapPage], id) == null)
          }
        }
      finally StandardServiceRegistryBuilder.destroy(registry)
    }
  }
