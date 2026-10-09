package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.runtime.{ManagedTranslationRows, SessionContentLocale}
import org.hibernate.{HibernateException, Session, SessionFactory}
import org.hibernate.event.spi.EventSource

import java.util.{HashMap, UUID}
import scala.jdk.CollectionConverters.*

/** Exact-locale editor access that leaves the managed domain entity state untouched. */
final class Translations[P <: AnyRef] private[runtime] (
  factory: SessionFactory,
  entityClass: Class[P],
  translationEntity: String,
  idOf: P => UUID,
  fields: Seq[TranslationField[P, ?]]
):
  def get[V](session: Session, page: P, field: TranslationField[P, V], locale: String): Option[V] =
    val source = checkedSession(session, page, field, locale)
    val row = ManagedTranslationRows.find(source, translationEntity, rowId(idOf(page), locale))
    Option(row).flatMap(value => Option(value.get(field.name))).flatMap(value => Option(field.decode(value)))

  def set[V](session: Session, page: P, field: TranslationField[P, V], locale: String, value: V): Unit =
    val source = checkedSession(session, page, field, locale)
    if locale == SessionContentLocale.required(source) then
      throw new HibernateException("Edit the active content locale through the domain entity")
    val id = rowId(idOf(page), locale)
    val row = ManagedTranslationRows.find(source, translationEntity, id)
    val encoded = if value == null then null else field.encode(value)
    if row == null then
      if encoded != null then
        val created = new HashMap[String, Object]()
        created.putAll(id)
        created.put(field.name, encoded)
        session.persist(translationEntity, created)
    else
      row.put(field.name, encoded)
      if fields.forall(member => row.get(member.name) == null) then session.remove(row)

  /** Copies every stored locale except the Session's active locale into a managed target.
    * Existing target rows for those locales are replaced field by field; other target locales
    * and the managed domain state are left untouched.
    */
  def copyInactive(session: Session, from: P, to: P): Int =
    val source = checkedEntity(session, from)
    checkedEntity(session, to)
    val fromId = idOf(from)
    val toId = idOf(to)
    require(fromId != toId, "Translation copy requires different source and target entities")
    val activeLocale = SessionContentLocale.required(source)
    val rows = source.createQuery(
      s"from $translationEntity t where t.pageId = :pageId and t.locale <> :activeLocale order by t.locale",
      classOf[java.util.Map[?, ?]]
    ).setParameter("pageId", fromId).setParameter("activeLocale", activeLocale)
      .getResultList.asScala.toVector
    rows.foreach { sourceRow =>
      val locale = sourceRow.get("locale").asInstanceOf[String]
      val id = rowId(toId, locale)
      val targetRow = ManagedTranslationRows.find(source, translationEntity, id)
      if targetRow == null then
        val created = new HashMap[String, Object]()
        created.putAll(id)
        fields.foreach(field => created.put(field.name, sourceRow.get(field.name)))
        session.persist(translationEntity, created)
      else
        fields.foreach(field => targetRow.put(field.name, sourceRow.get(field.name)))
    }
    rows.size

  private def checkedSession[V](
    session: Session,
    page: P,
    field: TranslationField[P, V],
    locale: String
  ): EventSource =
    val source = checkedEntity(session, page)
    require(locale != null && locale.matches("[A-Za-z0-9-]+"), s"Invalid locale tag: $locale")
    require(fields.exists(_ eq field), s"Translation field '${field.name}' was not installed")
    source

  private def checkedEntity(session: Session, page: P): EventSource =
    val source = session.asInstanceOf[EventSource]
    SessionContentLocale.required(source)
    require(session.getSessionFactory eq factory, "Translation access requires the SessionFactory used for installation")
    require(page != null && entityClass.isInstance(page) && session.contains(page),
      "Translation access requires a managed entity of the installed type")
    if idOf(page) == null then throw new HibernateException("Translation access requires a persistent identifier")
    source

  private def rowId(pageId: UUID, locale: String): HashMap[String, Object] =
    val id = new HashMap[String, Object]()
    id.put("pageId", pageId)
    id.put("locale", locale)
    id
