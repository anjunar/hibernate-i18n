package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale

import org.hibernate.HibernateException
import org.hibernate.boot.spi.SessionFactoryOptions
import org.hibernate.cache.cfg.spi.{DomainDataRegionBuildingContext, DomainDataRegionConfig}
import org.hibernate.cache.spi.{CacheTransactionSynchronization, DomainDataRegion, QueryResultsRegion, RegionFactory, TimestampsRegion}
import org.hibernate.cache.spi.access.AccessType
import org.hibernate.engine.spi.{SessionFactoryImplementor, SharedSessionContractImplementor}
private final case class LocaleQueryKey(locale: String, tenant: Object, original: Object) extends Serializable
