package com.anjunar.hibernatei18n.boot

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import org.hibernate.MappingException
import org.hibernate.boot.ResourceStreamLocator
import org.hibernate.boot.spi.{AdditionalMappingContributions, AdditionalMappingContributor, InFlightMetadataCollector, MetadataBuildingContext}

import scala.jdk.CollectionConverters.*

/** Rejects a plain Hibernate bootstrap that would persist translated values in the parent table. */
final class LocalizedBootstrapGuard extends AdditionalMappingContributor:
  override def contribute(
    contributions: AdditionalMappingContributions,
    metadata: InFlightMetadataCollector,
    resources: ResourceStreamLocator,
    buildingContext: MetadataBuildingContext
  ): Unit =
    metadata.getEntityBindings.asScala.foreach { binding =>
      if binding.getClassName != null then
        val entityClass = binding.getMappedClass
        var current: Class[?] = entityClass
        while current != null && current != classOf[Object] do
          if current.isAnnotationPresent(classOf[Localized]) ||
            current.getDeclaredFields.exists(_.isAnnotationPresent(classOf[Translation])) ||
            current.getDeclaredMethods.exists(_.isAnnotationPresent(classOf[Translation]))
          then throw unsupported(entityClass.getName)
          current = current.getSuperclass
    }

  private def unsupported(entityName: String): MappingException =
    new MappingException(
      "@Localized/@Translation requires the Hibernate I18n bootstrap for " + entityName +
        "; refusing to map translated values into the parent table. " +
        "Use HibernateI18n.registryBuilder() and install every localized entity before opening a Session."
    )
