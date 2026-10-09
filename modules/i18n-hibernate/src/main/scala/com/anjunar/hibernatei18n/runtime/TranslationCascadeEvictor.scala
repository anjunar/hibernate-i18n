package com.anjunar.hibernatei18n.runtime

import org.hibernate.event.spi.{EventSource, PreDeleteEvent, PreDeleteEventListener}

import java.util.UUID

/** Removes managed Map rows before the database cascades a parent deletion. */
private[runtime] final class TranslationCascadeEvictor[P <: AnyRef](
  parentClass: Class[P],
  translationEntity: String,
  idOf: P => UUID
) extends PreDeleteEventListener:
  override def onPreDelete(event: PreDeleteEvent): Boolean =
    if parentClass.isInstance(event.getEntity) then
      val session = event.getSession.asInstanceOf[EventSource]
      val id = idOf(parentClass.cast(event.getEntity))
      val rows = session.getPersistenceContextInternal.reentrantSafeEntityEntries().collect {
        case entry if entry.getValue.getPersister.getEntityName == translationEntity &&
            entry.getKey.isInstanceOf[java.util.Map[?, ?]] &&
            entry.getKey.asInstanceOf[java.util.Map[?, ?]].get("pageId") == id =>
          entry.getKey
      }
      rows.foreach(session.evict)
    false
