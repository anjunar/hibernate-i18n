package com.anjunar.hibernatei18n.runtime

import org.hibernate.{HibernateException, Session}
import org.hibernate.engine.spi.SharedSessionContractImplementor

/** Internal, immutable per-Session locale binding. The production mapping guard remains active. */
private[hibernatei18n] object SessionContentLocale:
  private val key = "com.anjunar.hibernatei18n.contentLocaleBinding"
  private final case class Binding(locale: String) extends Serializable

  private def validateInspector(session: SharedSessionContractImplementor, locale: String): Unit =
    val probe = "select '__hibernate_i18n_locale__', '__hibernate_i18n_language__'"
    val expected = s"select '$locale', '${locale.takeWhile(_ != '-')}'"
    val inspected = session.getJdbcSessionContext.getStatementInspector.inspect(probe)
    if inspected != expected then
      throw new IllegalArgumentException("The Session SQL inspector does not match its content locale")

  def bind(session: Session, locale: String): Unit = session.synchronized {
    require(locale.matches("[A-Za-z0-9-]+"), s"Invalid locale tag: $locale")
    if !session.isOpen then throw new HibernateException("Cannot bind a closed Session")
    session.getProperties.get(key) match
      case null =>
        validateInspector(session.asInstanceOf[SharedSessionContractImplementor], locale)
        session.setProperty(key, Binding(locale))
        if required(session.asInstanceOf[SharedSessionContractImplementor]) != locale then
          throw new HibernateException("The Session did not retain its content locale binding")
      case Binding(previous) if previous == locale =>
        validateInspector(session.asInstanceOf[SharedSessionContractImplementor], locale)
      case Binding(_) => throw new IllegalStateException("The content locale of a Session cannot change")
      case _          => throw new HibernateException("The Session content locale binding was replaced")
  }

  def required(session: SharedSessionContractImplementor): String = session match
    case stateful: Session => stateful.synchronized {
        stateful.getProperties.get(key) match
          case Binding(locale) =>
            validateInspector(session, locale)
            locale
          case null => throw new HibernateException("No content locale bound to the Session")
          case _    => throw new HibernateException("The Session content locale binding was replaced")
      }
    case _ => throw new HibernateException("A stateful Session is required for localized caching")
