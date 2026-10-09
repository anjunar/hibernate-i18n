package com.anjunar.hibernatei18n.boot

import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{Column, Convert, Entity, Table}
import org.hibernate.MappingException
import org.hibernate.dialect.Dialect
import org.hibernate.mapping.{Column as MappingColumn, Table as MappingTable}

import java.lang.reflect.Field

/** Internal mapping generator exercised by the isolated integration bootstrap. */
private[hibernatei18n] object TranslationMappingXml:
  private val identifier = "[A-Za-z_][A-Za-z0-9_]*".r
  private val stackTableIdentifier = "[A-Za-z_][A-Za-z0-9_#]*".r
  private val localeTag = "[A-Za-z0-9-]+".r

  private def safeIdentifier(value: String): String =
    if identifier.matches(value) then value
    else throw new MappingException(s"Unsupported translation SQL identifier: $value")

  private def tableIdentifier(value: String): String =
    if stackTableIdentifier.matches(value) then value
    else throw new MappingException(s"Unsupported translation table identifier: $value")

  private def xmlTableName(value: String): String =
    val checked = tableIdentifier(value)
    if identifier.matches(checked) then checked else s"`$checked`"

  private def sqlTableName(value: String): String =
    val checked = tableIdentifier(value)
    if identifier.matches(checked) then checked else s"\"$checked\""

  def translationEntityName(entity: Class[?]): String =
    "hibernate_i18n_generated." + entity.getName + ".translation"

  private def safeLocale(value: String): String =
    if localeTag.matches(value) then value
    else throw new MappingException(s"Unsupported fallback locale: $value")

  private def columnName(attribute: LocalizedEntityMembers.Attribute): String =
    val column = attribute.annotation(classOf[Column])
    safeIdentifier(if column != null && column.name().nonEmpty then column.name() else attribute.name)

  private def translatedColumnName(attribute: LocalizedEntityMembers.Attribute): String =
    val translation = attribute.annotation(classOf[Translation])
    safeIdentifier(if translation.column().nonEmpty then translation.column() else attribute.name)

  private def databaseType(attribute: LocalizedEntityMembers.Attribute): Class[?] =
    val conversion = attribute.annotation(classOf[Convert])
    if conversion == null then attribute.javaType
    else
      conversion.converter().getMethods.find(method =>
        method.getName == "convertToDatabaseColumn" && !method.isBridge
      ).map(_.getReturnType).getOrElse(
        throw new MappingException(s"Cannot resolve converter for ${attribute.name}")
      )

  def validateInheritance(entities: Seq[Class[?]]): Unit =
    val mapped = entities.distinct.filter(_.isAnnotationPresent(classOf[Entity]))
    mapped.filter(_.isAnnotationPresent(classOf[Localized])).foreach { localized =>
      LocalizedEntityMembers.inspect(localized).translations.iterator
        .map(_.declaringClass).filter(_ != localized).toSet.foreach { owner =>
          val users = mapped.filter(owner.isAssignableFrom)
          if users.size > 1 then
            throw new MappingException(
              s"Inherited @Translation in ${owner.getName} is shared by multiple entity mappings: " +
                users.map(_.getName).sorted.mkString(", ")
            )
        }
    }
    mapped.foreach { entity =>
      val hierarchy = Iterator.iterate(entity)(_.getSuperclass)
        .takeWhile(current => current != null && current != classOf[Object]).toSeq
      val translated = hierarchy.exists(current =>
        current.getDeclaredFields.exists(_.isAnnotationPresent(classOf[Translation])) ||
          current.getDeclaredMethods.exists(_.isAnnotationPresent(classOf[Translation]))
      )
      val localizedOwner = hierarchy.exists(current =>
        current.isAnnotationPresent(classOf[Entity]) && current.isAnnotationPresent(classOf[Localized])
      )
      if translated && !localizedOwner then
        throw new MappingException(s"@Translation requires a mapped @Localized entity owner: ${entity.getName}")
    }

  private[hibernatei18n] def formulaFor(
    translationTable: String,
    translationIdColumn: String,
    translationLocaleColumn: String,
    parentIdColumn: String,
    translationTenantColumn: Option[String],
    parentTenantColumn: Option[String],
    valueColumn: String,
    localization: Localized
  ): String =
    formulaForSqlNames(
      sqlTableName(translationTable), safeIdentifier(translationIdColumn),
      safeIdentifier(translationLocaleColumn), safeIdentifier(parentIdColumn),
      translationTenantColumn.map(safeIdentifier), parentTenantColumn.map(safeIdentifier),
      safeIdentifier(valueColumn), localization
    )

  private[hibernatei18n] def formulaForPhysical(
    table: MappingTable,
    translationId: MappingColumn,
    translationLocale: MappingColumn,
    parentId: MappingColumn,
    translationTenant: Option[MappingColumn],
    parentTenant: Option[MappingColumn],
    value: MappingColumn,
    localization: Localized,
    dialect: Dialect
  ): String =
    def tableName: String =
      if table.isQuoted then table.getQuotedName(dialect) else safeIdentifier(table.getName)
    def columnName(column: MappingColumn): String =
      if column.isQuoted then column.getQuotedName(dialect) else safeIdentifier(column.getName)
    formulaForSqlNames(
      tableName, columnName(translationId), columnName(translationLocale), columnName(parentId),
      translationTenant.map(columnName), parentTenant.map(columnName), columnName(value), localization
    )

  private def formulaForSqlNames(
    table: String,
    translationId: String,
    translationLocale: String,
    parentId: String,
    translationTenant: Option[String],
    parentTenant: Option[String],
    column: String,
    localization: Localized
  ): String =
    if translationTenant.isDefined != parentTenant.isDefined then
      throw new MappingException("Translation and parent tenant columns must be mapped together")
    val tenantPredicate = translationTenant.zip(parentTenant).map { (translation, parent) =>
      s" and t.$translation = {alias}.$parent"
    }.getOrElse("")
    val priorities = (Seq("__hibernate_i18n_locale__", "__hibernate_i18n_language__") ++
      Seq(localization.fallbackLocale(), localization.defaultLocale())
        .filter(_.nonEmpty).map(safeLocale)).distinct
    val selections = priorities.map { locale =>
      s"""(select t.$column from $table t where t.$translationId = {alias}.$parentId$tenantPredicate and t.$translationLocale = '$locale')"""
    }
    s"coalesce(${selections.mkString(", ")})"

  def mappingFor(entity: Class[?]): String =
    mappingFor(entity, entity.getSimpleName + "Translation")

  def mappingFor(entity: Class[?], translationEntity: String): String =
    if !entity.isAnnotationPresent(classOf[Entity]) || !entity.isAnnotationPresent(classOf[Localized]) then
      throw new MappingException(s"Expected @Entity @Localized: ${entity.getName}")
    val members = LocalizedEntityMembers.inspect(entity)
    val fields = members.translations
    if fields.exists(_.annotation(classOf[Column]) != null) then
      throw new MappingException("@Column on @Translation fields is not supported by this translation formula mapping; use @Translation(column = ...) for the translation-row column")
    if fields.exists(field => databaseType(field) != classOf[String]) then
      throw new MappingException("This experiment supports String database values only")
    if fields.exists(field => field.name == "rowVersion" || translatedColumnName(field) == "row_version") then
      throw new MappingException("rowVersion/row_version is reserved for the internal translation version")
    val tenantColumn = members.tenant.map(columnName)
    if tenantColumn.exists(Set("page_id", "locale", "row_version")) then
      throw new MappingException("The tenant column conflicts with an internal translation column")
    val translatedColumns = fields.map(translatedColumnName)
    if translatedColumns.exists(column => Set("page_id", "locale").contains(column) || tenantColumn.contains(column)) ||
        translatedColumns.distinct.size != translatedColumns.size then
      throw new MappingException("Translation columns must be distinct from each other and internal key columns")
    val tableAnnotation = entity.getAnnotation(classOf[Table])
    val parentTable = tableIdentifier(if tableAnnotation == null || tableAnnotation.name().isEmpty then entity.getSimpleName else tableAnnotation.name())
    val translationTable = tableIdentifier(parentTable + "_translation")
    val parentId = columnName(members.id)
    val localization = entity.getAnnotation(classOf[Localized])
    val access = if members.id.element.isInstanceOf[Field] then "FIELD" else "PROPERTY"
    def formula(field: LocalizedEntityMembers.Attribute): String =
      val column = translatedColumnName(field)
      val expression = formulaFor(translationTable, "page_id", "locale", parentId,
        tenantColumn, tenantColumn, column, localization)
      s"""<basic name="${field.name}"><formula>$expression</formula></basic>"""
    val formulas = fields.filter(_.declaringClass == entity).map(formula).mkString("\n")
    val inheritedMappings = fields.filter(_.declaringClass != entity)
      .groupBy(_.declaringClass).toSeq.sortBy(_._1.getName).map { (owner, members) =>
        val attributes = members.map(formula).mkString("\n")
        s"""<mapped-superclass class="${owner.getName}" access="$access">
           |  <attributes>$attributes</attributes>
           |</mapped-superclass>""".stripMargin
      }.mkString("\n")
    val translationFields = fields.map { field =>
      val column = translatedColumnName(field)
      s"""<basic name="${field.name}"><column name="$column" column-definition="text"/><target>java.lang.String</target></basic>"""
    }.mkString("\n")
    val tenantMapping = tenantColumn.fold("")(name =>
      s"""<tenant-id name="tenantId"><column name="$name"/><target>java.lang.String</target></tenant-id>"""
    )
    s"""<entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
       |  $inheritedMappings
       |  <entity class="${entity.getName}" access="$access">
       |    <synchronize table="${xmlTableName(translationTable)}"/>
       |    <attributes>$formulas</attributes>
       |  </entity>
       |  <entity name="$translationEntity" metadata-complete="true">
       |    <table name="${xmlTableName(translationTable)}"/>
       |    $tenantMapping
       |    <attributes>
       |      <id name="pageId"><column name="page_id"/><target>java.util.UUID</target></id>
       |      <id name="locale"><column name="locale"/><target>java.lang.String</target></id>
       |      $translationFields
       |    </attributes>
       |  </entity>
       |</entity-mappings>""".stripMargin
