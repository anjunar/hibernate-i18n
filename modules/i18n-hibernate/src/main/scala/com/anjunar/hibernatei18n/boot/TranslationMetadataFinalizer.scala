package com.anjunar.hibernatei18n.boot

import com.anjunar.hibernatei18n.annotation.Localized
import org.hibernate.MappingException
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.boot.spi.{InFlightMetadataCollector, MetadataBuildingContext}
import org.hibernate.engine.OptimisticLockStyle
import org.hibernate.mapping.{BasicValue, Column, Formula, PersistentClass, Property, RootClass}

import java.util
import scala.jdk.CollectionConverters.*

/** Internal late binding of physical names, FK and version; deliberately not registered as a service. */
private[hibernatei18n] object TranslationMetadataFinalizer:
  def complete(
    parent: PersistentClass,
    metadata: InFlightMetadataCollector,
    buildingContext: MetadataBuildingContext
  ): Unit =
    complete(parent, metadata, buildingContext, parent.getMappedClass.getSimpleName + "Translation")

  def complete(
    parent: PersistentClass,
    metadata: InFlightMetadataCollector,
    buildingContext: MetadataBuildingContext,
    entityName: String
  ): Unit =
    val members = LocalizedEntityMembers.inspect(parent.getMappedClass)
    members.translations.iterator.map(_.declaringClass)
      .filter(_ != parent.getMappedClass).toSet.foreach { owner =>
        val users = metadata.getEntityBindings.asScala.iterator
          .filter(_.getClassName != null).map(_.getMappedClass)
          .filter(owner.isAssignableFrom).toSeq
        if users.size > 1 then
          throw new MappingException(
            s"Inherited @Translation in ${owner.getName} is shared by multiple entity mappings: " +
              users.map(_.getName).sorted.mkString(", ")
          )
      }
    val translation = metadata.getEntityBinding(entityName)
    if translation == null then throw new MappingException(s"Missing generated translation mapping: $entityName")
    def singleColumn(columns: util.List[Column], label: String): Column =
      if columns.size() != 1 then throw new MappingException(s"Expected one column for $label")
      columns.get(0)
    val pageIdColumn = singleColumn(translation.getProperty("pageId").getValue.getColumns, s"$entityName.pageId")
    val localeColumn = singleColumn(translation.getProperty("locale").getValue.getColumns, s"$entityName.locale")
    val parentIdColumn = singleColumn(parent.getIdentifier.getColumns, s"${parent.getClassName} ID")
    val tenantColumns = members.tenant.map { tenantField =>
      val parentColumn = singleColumn(
        parent.getProperty(tenantField.name).getValue.getColumns,
        s"${parent.getClassName}.${tenantField.name}"
      )
      val translationColumn = singleColumn(
        translation.getProperty("tenantId").getValue.getColumns,
        s"$entityName.tenantId"
      )
      // The composite FK must use the same SQL type as the parent's @TenantId column.
      // XML defaults String to varchar(255), while an application may declare a shorter tenant ID.
      translationColumn.setLength(parentColumn.getLength)
      (translationColumn, parentColumn)
    }
    val localization = parent.getMappedClass.getAnnotation(classOf[Localized])
    val dialect = metadata.getDatabase.getDialect
    val physicalVersionName = buildingContext.getBuildingOptions.getPhysicalNamingStrategy
      .toPhysicalColumnName(metadata.getDatabase.toIdentifier("row_version"), metadata.getDatabase.getJdbcEnvironment)
    if physicalVersionName == null then throw new MappingException("No physical name for translation row_version")
    val reservedColumns = Seq(pageIdColumn, localeColumn) ++ tenantColumns.map(_._1)
    def collisionName(column: Column): String = column.getName.toLowerCase(util.Locale.ROOT)
    val occupiedNames =
      (reservedColumns.map(collisionName) :+ physicalVersionName.getText.toLowerCase(util.Locale.ROOT)).toSet
    val valueColumns = members.translations.map { field =>
      field -> singleColumn(
        translation.getProperty(field.name).getValue.getColumns,
        s"$entityName.${field.name}"
      )
    }
    val valueNames = valueColumns.map { case (_, column) => collisionName(column) }
    if valueNames.exists(occupiedNames.contains) || valueNames.distinct.size != valueNames.size then
      throw new MappingException(s"Translation columns of $entityName collide after physical naming")
    valueColumns.foreach { (field, valueColumn) =>
      val selectables = parent.getProperty(field.name).getValue.getSelectables
      if selectables.size() != 1 || !selectables.get(0).isInstanceOf[Formula] then
        throw new MappingException(s"Expected one formula for ${parent.getClassName}.${field.name}")
      selectables.get(0).asInstanceOf[Formula].setFormula(
        TranslationMappingXml.formulaForPhysical(
          translation.getTable,
          pageIdColumn,
          localeColumn,
          parentIdColumn,
          tenantColumns.map(_._1),
          tenantColumns.map(_._2),
          valueColumn,
          localization,
          dialect
        )
      )
    }
    parent.addSynchronizedTable(translation.getTable.getQuotedName(dialect))
    val (foreignColumns, referencedColumns) = members.tenant match
      case None    => (util.List.of(pageIdColumn), null)
      case Some(_) =>
        val (translationTenantColumn, parentTenantColumn) = tenantColumns.get
        val parentColumns = util.List.of(parentIdColumn, parentTenantColumn)
        parent.getTable.createUniqueKey(parentColumns, buildingContext)
        (util.List.of(pageIdColumn, translationTenantColumn), parentColumns)
    val foreignKey = translation.getTable.createForeignKey(
      s"fk_${translation.getTable.getName}_${parent.getTable.getName}",
      foreignColumns,
      parent.getClassName,
      null,
      null,
      referencedColumns
    )
    foreignKey.setReferencedTable(parent.getTable)
    foreignKey.setOnDeleteAction(OnDeleteAction.CASCADE)
    val root = translation.asInstanceOf[RootClass]
    val versionColumn = new Column(physicalVersionName.render())
    versionColumn.setNullable(false)
    translation.getTable.addColumn(versionColumn)
    val versionValue = new BasicValue(buildingContext, translation.getTable)
    versionValue.addColumn(versionColumn)
    versionValue.setTypeName("long")
    val versionProperty = new Property()
    versionProperty.setName("rowVersion")
    versionProperty.setValue(versionValue)
    versionProperty.setPersistentClass(root)
    versionProperty.setOptional(false)
    root.addProperty(versionProperty)
    root.setVersion(versionProperty)
    root.setDeclaredVersion(versionProperty)
    root.setOptimisticLockStyle(OptimisticLockStyle.VERSION)
