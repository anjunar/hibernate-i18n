import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernatei18n.runtime.{TranslationField, HibernateI18n, Translations}
import jakarta.persistence.{Entity, Id, Table}
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.util.UUID
import javax.sql.DataSource
import scala.compiletime.uninitialized
object RuntimeConsumer:
  def withFactory[A](dataSource: DataSource, schemaMode: String = "none")(
    body: (SessionFactory, Translations[ExamplePage],
      TranslationField[ExamplePage, String]) => A
  ): A =
    val registry = HibernateI18n.registryBuilder()
      .applySetting("hibernate.connection.datasource", dataSource)
      .applySetting("hibernate.hbm2ddl.auto", schemaMode)
      .applySetting("hibernate.cache.use_query_cache", "false")
      .build()
    try
      val factory = new MetadataSources(registry)
        .addAnnotatedClass(classOf[ExamplePage])
        .buildMetadata().buildSessionFactory()
      try
        val translations = HibernateI18n.translations(factory, classOf[ExamplePage])
        val titleField = translations.field[String]("title")
        body(factory, translations, titleField)
      finally factory.close()
    finally StandardServiceRegistryBuilder.destroy(registry)

  def germanTitle(factory: SessionFactory, id: UUID): String =
    val session = HibernateI18n.openSession(factory, "de")
    try session.find(classOf[ExamplePage], id).title
    finally session.close()

  def englishTitleInGermanSession(
    factory: SessionFactory,
    translations: Translations[ExamplePage],
    titleField: TranslationField[ExamplePage, String],
    id: UUID
  ): Option[String] =
    val session = HibernateI18n.openSession(factory, "de")
    try
      val page = session.find(classOf[ExamplePage], id)
      translations.get(session, page, titleField, "en")
    finally session.close()

  def setEnglishTitleInGermanSession(
    factory: SessionFactory,
    translations: Translations[ExamplePage],
    titleField: TranslationField[ExamplePage, String],
    id: UUID,
    title: String
  ): Unit =
    val session = HibernateI18n.openSession(factory, "de")
    try
      val transaction = session.beginTransaction()
      try
        val page = session.find(classOf[ExamplePage], id)
        translations.set(session, page, titleField, "en", title)
        transaction.commit()
      catch
        case error: Throwable =>
          if transaction.isActive then transaction.rollback()
          throw error
    finally session.close()
