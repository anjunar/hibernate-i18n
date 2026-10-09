package com.anjunar.hibernatei18n.integration

import org.hibernate.HibernateException
import org.hibernate.engine.spi.EntityEntry
import org.hibernate.event.spi.EventSource

import java.util.{IdentityHashMap, Objects, WeakHashMap}

/** Test-only snapshot for formula fields, which Hibernate deliberately treats as read-only. */
object TranslationChangeTracker:
  final case class Changes(title: Boolean, content: Boolean):
    def any: Boolean = title || content

final class TranslationChangeTracker[P <: AnyRef](titleOf: P => String, contentOf: P => Markdown):
  private final case class Values(title: String, content: Markdown)
  private val snapshots = new WeakHashMap[EventSource, IdentityHashMap[P, Values]]()

  def changed(session: EventSource, page: P, entry: EntityEntry): TranslationChangeTracker.Changes = synchronized {
    val previous = Option(snapshots.get(session)).flatMap(values => Option(values.get(page)))
      .getOrElse(initialValues(entry))
    TranslationChangeTracker.Changes(
      !Objects.equals(previous.title, titleOf(page)),
      !Objects.equals(previous.content, contentOf(page))
    )
  }

  def synchronizedValue(session: EventSource, page: P): Unit = synchronized {
    var values = snapshots.get(session)
    if values == null then
      values = new IdentityHashMap[P, Values]()
      snapshots.put(session, values)
    values.put(page, Values(titleOf(page), contentOf(page)))
  }

  def cleared(session: EventSource): Unit = synchronized {
    snapshots.remove(session)
  }

  def evicted(session: EventSource, entity: AnyRef): Unit = synchronized {
    val values = snapshots.get(session)
    if values != null then
      values.remove(entity)
      if values.isEmpty then snapshots.remove(session)
  }

  private def initialValues(entry: EntityEntry): Values =
    if !entry.isExistsInDatabase then Values(null, null)
    else
      val loaded = entry.getLoadedState
      if loaded == null then throw new HibernateException("Formula translation state has no loaded snapshot")
      val names = entry.getPersister.getPropertyNames
      val titleIndex = names.indexOf("title")
      val contentIndex = names.indexOf("content")
      if titleIndex < 0 || contentIndex < 0 then
        throw new HibernateException("Formula translation properties are not mapped")
      Values(loaded(titleIndex).asInstanceOf[String], loaded(contentIndex).asInstanceOf[Markdown])
