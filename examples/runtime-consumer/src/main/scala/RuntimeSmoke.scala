import com.anjunar.hibernatei18n.runtime.HibernateI18n
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

import java.nio.file.Files
import java.util.UUID
import scala.util.Using

/** End-to-end consumer of the packaged runtime artifact. */
object RuntimeSmoke:
  def main(args: Array[String]): Unit =
    val target = java.nio.file.Path.of("target").toAbsolutePath
    Files.createDirectories(target)
    Using.resource(EmbeddedPostgres.builder()
      .setDataDirectory(Files.createTempDirectory(target, "i18n-consumer-pg-"))
      .setServerConfig("listen_addresses", "127.0.0.1")
      .setPort(0).start()) { postgres =>
      RuntimeConsumer.withFactory(postgres.getPostgresDatabase, schemaMode = "create-drop") {
        (factory, translations) =>
          val id = UUID.randomUUID()
          Using.resource(HibernateI18n.openSession(factory, "de")) { session =>
            val transaction = session.beginTransaction()
            try
              val page = new ExamplePage()
              page.id = id
              page.title = "Hallo"
              session.persist(page)
              transaction.commit()
            catch
              case error: Throwable =>
                if transaction.isActive then transaction.rollback()
                throw error
          }
          RuntimeConsumer.setEnglishTitleInGermanSession(
            factory, translations, id, "Hello")
          val german = RuntimeConsumer.germanTitle(factory, id)
          val editor = RuntimeConsumer.englishTitleInGermanSession(
            factory, translations, id)
          val english = Using.resource(HibernateI18n.openSession(factory, "en")) { session =>
            session.find(classOf[ExamplePage], id).title
          }
          require(german == "Hallo" && editor.contains("Hello") && english == "Hello",
            s"Unexpected translations: de=$german, en=$english, editor=$editor")
          val draftId = UUID.randomUUID()
          Using.resource(HibernateI18n.openSession(factory, "de")) { session =>
            val transaction = session.beginTransaction()
            try
              val source = session.find(classOf[ExamplePage], id)
              val draft = new ExamplePage()
              draft.id = draftId
              draft.title = "Entwurf"
              session.persist(draft)
              require(translations.copyInactive(session, source, draft) == 1)
              transaction.commit()
            catch
              case error: Throwable =>
                if transaction.isActive then transaction.rollback()
                throw error
          }
          val draftGerman = RuntimeConsumer.germanTitle(factory, draftId)
          val draftEnglish = Using.resource(HibernateI18n.openSession(factory, "en")) { session =>
            session.find(classOf[ExamplePage], draftId).title
          }
          require(draftGerman == "Entwurf" && draftEnglish == "Hello",
            s"Unexpected draft translations: de=$draftGerman, en=$draftEnglish")
          println(s"runtime-smoke: de=$german, en=$english, editor=${editor.get}, " +
            s"draft-de=$draftGerman, draft-en=$draftEnglish")
      }
    }
