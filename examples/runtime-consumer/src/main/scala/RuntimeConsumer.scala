import com.anjunar.hibernatei18n.runtime.{HibernateI18n, Translations}
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.util
import javax.sql.DataSource
object RuntimeConsumer:
  def withFactory[A](dataSource: DataSource, schemaMode: String = "none")(
    body: (SessionFactory, Translations[ExamplePage]) => A
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
        body(factory, translations)
      finally factory.close()
    finally StandardServiceRegistryBuilder.destroy(registry)

  def germanTitle(factory: SessionFactory, id: util.UUID): String =
    val session = HibernateI18n.openSession(factory, "de")
    try session.find(classOf[ExamplePage], id).title
    finally session.close()

  def englishTitleInGermanSession(
    factory: SessionFactory,
    translations: Translations[ExamplePage],
    id: util.UUID
  ): Option[String] =
    val session = HibernateI18n.openSession(factory, "de")
    try
      val page = session.find(classOf[ExamplePage], id)
      translations.get[String](session, page, "title", "en")
    finally session.close()

  def setEnglishTitleInGermanSession(
    factory: SessionFactory,
    translations: Translations[ExamplePage],
    id: util.UUID,
    title: String
  ): Unit =
    val session = HibernateI18n.openSession(factory, "de")
    try
      val transaction = session.beginTransaction()
      try
        val page = session.find(classOf[ExamplePage], id)
        translations.set(session, page, "title", "en", title)
        transaction.commit()
      catch
        case error: Throwable =>
          if transaction.isActive then transaction.rollback()
          throw error
    finally session.close()
