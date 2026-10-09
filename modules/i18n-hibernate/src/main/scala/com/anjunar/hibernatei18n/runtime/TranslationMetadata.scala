package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{TranslationCachePolicy, TranslationMappingXml, TranslationMetadataFinalizer}
import org.hibernate.MappingException
import org.hibernate.boot.ResourceStreamLocator
import org.hibernate.boot.spi.{AdditionalMappingContributions, AdditionalMappingContributor, InFlightMetadataCollector, MetadataBuildingContext}

import scala.jdk.CollectionConverters.*

/** Only installed by HibernateI18n.registryBuilder. */
final class TranslationMetadata extends AdditionalMappingContributor:
  override def contribute(
    contributions: AdditionalMappingContributions,
    metadata: InFlightMetadataCollector,
    resources: ResourceStreamLocator,
    buildingContext: MetadataBuildingContext
  ): Unit =
    val settings = buildingContext.getBootstrapContext.getConfigurationService.getSettings
    val queryCache = java.lang.Boolean.parseBoolean(String.valueOf(settings.get("hibernate.cache.use_query_cache")))
    val bindings = metadata.getEntityBindings.asScala.toSeq
    TranslationMappingXml.validateInheritance(bindings
      .filter(_.getClassName != null).map(_.getMappedClass))
    bindings.foreach { parent =>
      if parent.getClassName != null && parent.getMappedClass.isAnnotationPresent(classOf[Localized]) then
        if queryCache then throw new MappingException("The Hibernate I18n runtime does not support query caching")
        val root = parent.getRootClass
        bindings.filter(_.getRootClass eq root).foreach(_.setCached(false))
        root.setCacheConcurrencyStrategy(null)
        root.setCacheRegionName(null)
        TranslationCachePolicy.validate(parent, queryCache, false)
        TranslationMetadataFinalizer.complete(parent, metadata, buildingContext,
          TranslationMappingXml.translationEntityName(parent.getMappedClass))
    }
