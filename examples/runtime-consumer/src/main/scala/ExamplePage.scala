import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import jakarta.persistence.{Entity, Id, Table}

import java.util.UUID
import scala.compiletime.uninitialized

@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
@Table(name = "example_page")
class ExamplePage:
  @Id
  var id: UUID = uninitialized
  @Translation
  var title: String = uninitialized

