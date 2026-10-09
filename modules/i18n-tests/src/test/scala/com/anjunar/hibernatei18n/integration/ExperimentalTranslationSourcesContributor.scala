package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.TranslationMappingXml
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService
import org.hibernate.boot.spi.MetadataSourcesContributor

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Test-only opt-in for the mapping generator; the published module keeps its guard. */
final class ExperimentalTranslationSourcesContributor extends MetadataSourcesContributor:
  override def contribute(sources: MetadataSources): Unit =
    val loader = sources.getServiceRegistry.requireService(classOf[ClassLoaderService])
    val entities = sources.getAnnotatedClasses.asScala.toSeq ++
      sources.getAnnotatedClassNames.asScala.toSeq.map(loader.classForName(_))
    TranslationMappingXml.validateInheritance(entities)
    entities.distinct.filter(_.isAnnotationPresent(classOf[Localized])).foreach { entity =>
      val xml = TranslationMappingXml.mappingFor(entity)
      sources.addInputStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
    }
