import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernatei18n.runtime.{TranslationField, HibernateI18n, Translations}
import jakarta.persistence.{Entity, Id, Table}
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

import java.util.UUID
import javax.sql.DataSource
import scala.compiletime.uninitialized

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "example_page")
class ExamplePage:
  @Id var id: UUID = uninitialized
  @Translation var title: String = uninitialized

