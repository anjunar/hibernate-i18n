import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{Entity, Id, Table}

import java.util
import scala.compiletime.uninitialized

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "example_page")
class ExamplePage:
  @Id
  var id: util.UUID = uninitialized
  @Translation
  var title: String = uninitialized
