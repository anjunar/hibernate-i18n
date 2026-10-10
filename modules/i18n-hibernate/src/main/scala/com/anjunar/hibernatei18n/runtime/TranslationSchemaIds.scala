package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernateddl.core.SchemaId
import com.anjunar.hibernateddl.hibernate.{ClasslessEntitySchemaIdProvider, ClasslessEntitySchemaIds}
import com.anjunar.hibernateddl.hibernate.annotation.{SchemaId as StableId}
import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{LocalizedEntityMembers, TranslationMappingXml}
import org.hibernate.boot.Metadata
import org.hibernate.mapping.{Column, PersistentClass}

import scala.jdk.CollectionConverters.*

/** Stable schema IDs for the runtime's generated translation-row entities. */
final class TranslationSchemaIds extends ClasslessEntitySchemaIdProvider:
  override def identify(entity: PersistentClass, metadata: Metadata): Option[ClasslessEntitySchemaIds] =
    if entity.getClassName != null || !entity.getEntityName.startsWith("hibernate_i18n_generated.") then None
    else
      val parent = metadata.getEntityBindings.asScala.find { binding =>
        binding.getClassName != null && binding.getMappedClass.isAnnotationPresent(classOf[Localized]) &&
        TranslationMappingXml.translationEntityName(binding.getMappedClass) == entity.getEntityName
      }.getOrElse(throw new IllegalArgumentException(
        s"No @Localized parent owns generated entity ${entity.getEntityName}"
      ))
      val owner = parent.getMappedClass
      val parentId = Option(owner.getAnnotation(classOf[StableId])).map(_.value)
        .filter(_.matches("[0-9a-f]{8}"))
        .getOrElse(throw new IllegalArgumentException(
          s"@Localized entity ${owner.getName} needs an eight-digit @SchemaId for DDL manager integration"
        ))
      val tableId = SchemaId(s"$parentId/translation")
      def column(property: String): Column =
        val columns = entity.getProperty(property).getValue.getColumns
        if columns.size() != 1 then
          throw new IllegalArgumentException(
            s"Generated property ${entity.getEntityName}.$property must have exactly one column"
          )
        columns.get(0)
      val internal = Vector(
        column("pageId").getName -> SchemaId(s"${tableId.value}/page_id"),
        column("locale").getName -> SchemaId(s"${tableId.value}/locale"),
        column("rowVersion").getName -> SchemaId(s"${tableId.value}/row_version")
      ) ++ LocalizedEntityMembers.inspect(owner).tenant.toVector.map(_ =>
        column("tenantId").getName -> SchemaId(s"${tableId.value}/tenant_id")
      )
      val translated = LocalizedEntityMembers.inspect(owner).translations.map { member =>
        val id = Option(member.annotation(classOf[StableId])).map(_.value)
          .filter(_.matches("[0-9a-f]{8}"))
          .getOrElse(throw new IllegalArgumentException(
            s"@Translation ${owner.getName}.${member.name} needs an eight-digit @SchemaId for DDL manager integration"
          ))
        column(member.name).getName -> SchemaId(s"${tableId.value}/$id")
      }
      Some(ClasslessEntitySchemaIds(tableId, (internal ++ translated).toMap))
