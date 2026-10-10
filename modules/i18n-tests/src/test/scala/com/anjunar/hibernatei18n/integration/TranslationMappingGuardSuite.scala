package com.anjunar.hibernatei18n.integration

import org.hibernate.MappingException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import munit.FunSuite

class TranslationMappingGuardSuite extends FunSuite:
  test("an unsupported @Translation mapping fails before metadata can be used for DDL") {
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val sources = new MetadataSources(registry)
        .addAnnotatedClass(classOf[LocalizedPage])
      val error = intercept[MappingException] {
        sources.buildMetadata()
      }
      assertEquals(sources.getMappingXmlBindings.size(), 1)
      assert(error.getMessage.contains("@Localized/@Translation requires the Hibernate I18n bootstrap"))
      assert(error.getMessage.contains("HibernateI18n.registryBuilder()"))
      assert(error.getMessage.contains("refusing to map translated values into the parent table"))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("a Scala @Translation field is rejected without @Localized on the class") {
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val error = intercept[MappingException] {
        new MetadataSources(registry)
          .addAnnotatedClass(classOf[UnlocalizedTranslatedPage])
          .buildMetadata()
      }
      assert(error.getMessage.contains(classOf[UnlocalizedTranslatedPage].getName))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }

  test("a JavaBean @Translation getter is rejected without @Localized on the class") {
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .build()
    try
      val error = intercept[MappingException] {
        new MetadataSources(registry)
          .addAnnotatedClass(classOf[UnlocalizedPropertyPage])
          .buildMetadata()
      }
      assert(error.getMessage.contains(classOf[UnlocalizedPropertyPage].getName))
    finally StandardServiceRegistryBuilder.destroy(registry)
  }
