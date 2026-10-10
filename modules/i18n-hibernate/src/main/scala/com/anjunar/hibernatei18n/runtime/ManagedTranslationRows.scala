package com.anjunar.hibernatei18n.runtime

import org.hibernate.HibernateException
import org.hibernate.engine.spi.Status
import org.hibernate.event.spi.EventSource

import java.util
import scala.jdk.CollectionConverters.*

/** Internal lookup for classless translation rows already held by a persistence context. */
private[hibernatei18n] object ManagedTranslationRows:
  def managed(
    session: EventSource,
    entityName: String,
    id: util.Map[String, Object]
  ): Option[util.Map[String, Object]] =
    val matches = session.getPersistenceContextInternal.reentrantSafeEntityEntries().collect {
      case entry
          if entry.getValue.getStatus == Status.MANAGED &&
            entry.getValue.getPersister.getEntityName == entityName &&
            entry.getKey.isInstanceOf[util.Map[?, ?]] =>
        entry.getKey.asInstanceOf[util.Map[String, Object]]
    }.filter(row => id.asScala.forall { (key, value) => util.Objects.equals(row.get(key), value) })
    if matches.length > 1 then
      throw new HibernateException("Multiple managed translation rows have the same identifier")
    matches.headOption

  def find(session: EventSource, entityName: String, id: util.Map[String, Object]): util.Map[String, Object] =
    // Hibernate may return null from find(id) for a newly persisted dynamic Map row in the same Session.
    managed(session, entityName, id).getOrElse(
      session.find(entityName, id).asInstanceOf[util.Map[String, Object]]
    )
