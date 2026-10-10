package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{LocalizedEntityMembers, TranslationMappingXml}
import org.hibernate.boot.Metadata
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.cfg.MappingSettings
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.engine.config.spi.{ConfigurationService, StandardConverters}

import java.sql.Connection
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Explicit PostgreSQL upgrade for translation columns created by older development snapshots.
  * Run after building Metadata and before building the SessionFactory.
  */
object TranslationSchemaUpgrade:
  private final case class TextColumn(schema: String, table: String, column: String)

  /** Widen existing generated VARCHAR translation value columns to TEXT, preserving their data.
    * Missing tables/columns and columns already typed as TEXT are left alone. All changes commit
    * together; an unexpected existing type fails and rolls back instead of replacing data.
    *
    * The supplied DataSource must provide a connection with auto-commit enabled, so this method
    * owns the transaction. The metadata must use the same PostgreSQL database and schema.
    * @return the number of columns widened
    */
  def widenTextColumns(metadata: Metadata, dataSource: DataSource): Int =
    require(metadata != null, "Hibernate Metadata is required")
    require(dataSource != null, "A DataSource is required")
    val database = metadata.getDatabase
    require(
      database.getDialect.isInstanceOf[PostgreSQLDialect],
      "Translation text upgrade currently supports PostgreSQL only"
    )
    val names = database.getJdbcEnvironment.getIdentifierHelper
    val configuredSchema = database.getServiceRegistry.requireService(classOf[ConfigurationService])
      .getSetting(MappingSettings.DEFAULT_SCHEMA, StandardConverters.STRING)
    val defaultSchema = Option(names.toIdentifier(configuredSchema))
      .orElse(Option(database.getPhysicalImplicitNamespaceName.schema()))
    def physical(identifier: Identifier): String = names.toMetaDataObjectName(identifier)

    Using.resource(dataSource.getConnection) { connection =>
      require(connection.getAutoCommit, "Translation text upgrade requires an auto-commit DataSource connection")
      val schema = defaultSchema.map(physical).getOrElse(connection.getSchema)
      val targets = metadata.getEntityBindings.asScala.toVector
        .filter(binding =>
          binding.getClassName != null &&
            binding.getMappedClass.isAnnotationPresent(classOf[Localized])
        )
        .flatMap { parent =>
          val generated = metadata.getEntityBinding(
            TranslationMappingXml.translationEntityName(parent.getMappedClass)
          )
          if generated == null then
            throw new IllegalArgumentException(s"Missing generated translation mapping for ${parent.getClassName}")
          val table = generated.getTable
          val tableSchema = Option(table.getSchemaIdentifier).map(physical).getOrElse(schema)
          LocalizedEntityMembers.inspect(parent.getMappedClass).translations.map { field =>
            val columns = generated.getProperty(field.name).getValue.getColumns
            if columns.size() != 1 || !columns.get(0).getSqlType(metadata).equalsIgnoreCase("text") then
              throw new IllegalArgumentException(
                s"Expected one TEXT translation column for ${parent.getClassName}.${field.name}"
              )
            TextColumn(
              tableSchema,
              physical(table.getNameIdentifier),
              physical(Identifier.toIdentifier(columns.get(0).getName, columns.get(0).isQuoted))
            )
          }
        }.distinct
      connection.setAutoCommit(false)
      try
        val changed = targets.count(target => widenIfNeeded(connection, target))
        connection.commit()
        changed
      catch
        case NonFatal(error) =>
          connection.rollback()
          throw error
      finally connection.setAutoCommit(true)
    }

  private def widenIfNeeded(connection: Connection, target: TextColumn): Boolean =
    val currentType = Using.resource(connection.prepareStatement(
      "select data_type from information_schema.columns " +
        "where table_schema = ? and table_name = ? and column_name = ?"
    )) { statement =>
      statement.setString(1, target.schema)
      statement.setString(2, target.table)
      statement.setString(3, target.column)
      Using.resource(statement.executeQuery()) { rows =>
        if rows.next() then Some(rows.getString(1)) else None
      }
    }
    currentType match
      case None | Some("text")       => false
      case Some("character varying") =>
        def quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        val table = s"${quote(target.schema)}.${quote(target.table)}"
        Using.resource(connection.createStatement()) { statement =>
          statement.execute(s"alter table $table alter column ${quote(target.column)} type text")
        }
        true
      case Some(other) =>
        throw new IllegalStateException(
          s"Refusing to change ${target.schema}.${target.table}.${target.column} from $other to text"
        )
