package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{TranslationCachePolicy, TranslationMappingXml, TranslationMetadataFinalizer}
import org.hibernate.boot.ResourceStreamLocator
import org.hibernate.boot.spi.{AdditionalMappingContributions, AdditionalMappingContributor, InFlightMetadataCollector, MetadataBuildingContext}

import scala.jdk.CollectionConverters.*
import java.lang.{Boolean as JavaBoolean}

/** Test-only opt-in and cache policy around the inactive production metadata finalizer. */
final class ExperimentalTranslationMetadataContributor extends AdditionalMappingContributor:
  private val unsafeCacheProbeSetting = "hibernate.i18n.experimental.allow_unsafe_cache_probe"

  override def contribute(
    contributions: AdditionalMappingContributions,
    metadata: InFlightMetadataCollector,
    resources: ResourceStreamLocator,
    buildingContext: MetadataBuildingContext
  ): Unit =
    val settings = buildingContext.getBootstrapContext.getConfigurationService.getSettings
    def enabled(name: String): Boolean = JavaBoolean.parseBoolean(String.valueOf(settings.get(name)))
    val unsafeCacheProbe = enabled(unsafeCacheProbeSetting)
    val localeAwareQueryRegion = settings.get("hibernate.cache.region.factory_class") match
      case _: LocaleAwareRegionFactory => true
      case _                           => false
    TranslationMappingXml.validateInheritance(metadata.getEntityBindings.asScala.toSeq
      .filter(_.getClassName != null).map(_.getMappedClass))
    metadata.getEntityBindings.asScala.toSeq.foreach { parent =>
      if parent.getClassName != null && parent.getMappedClass.isAnnotationPresent(classOf[Localized]) then
        if !unsafeCacheProbe then
          TranslationCachePolicy.validate(parent, enabled("hibernate.cache.use_query_cache"), localeAwareQueryRegion)
        TranslationMetadataFinalizer.complete(parent, metadata, buildingContext)
    }
