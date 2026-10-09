package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale

import org.hibernate.HibernateException
import org.hibernate.boot.spi.SessionFactoryOptions
import org.hibernate.cache.cfg.spi.{DomainDataRegionBuildingContext, DomainDataRegionConfig}
import org.hibernate.cache.spi.{CacheTransactionSynchronization, DomainDataRegion, QueryResultsRegion, RegionFactory, TimestampsRegion}
import org.hibernate.cache.spi.access.AccessType
import org.hibernate.engine.spi.{SessionFactoryImplementor, SharedSessionContractImplementor}

/** Test-only decorator: preserves the provider's regions, but scopes query results by locale. */
final class LocaleAwareRegionFactory(delegate: RegionFactory) extends RegionFactory:
  override def start(settings: SessionFactoryOptions, configValues: java.util.Map[String, Object]): Unit =
    delegate.start(settings, configValues)

  override def stop(): Unit = delegate.stop()

  override def isMinimalPutsEnabledByDefault: Boolean = delegate.isMinimalPutsEnabledByDefault

  override def getDefaultAccessType: AccessType = delegate.getDefaultAccessType

  override def qualify(regionName: String): String = delegate.qualify(regionName)

  override def createTransactionContext(session: SharedSessionContractImplementor): CacheTransactionSynchronization =
    delegate.createTransactionContext(session)

  override def nextTimestamp(): Long = delegate.nextTimestamp()

  override def getTimeout(): Long = delegate.getTimeout()

  override def buildDomainDataRegion(
      regionConfig: DomainDataRegionConfig,
      buildingContext: DomainDataRegionBuildingContext
  ): DomainDataRegion = delegate.buildDomainDataRegion(regionConfig, buildingContext)

  override def buildTimestampsRegion(
      regionName: String,
      sessionFactory: SessionFactoryImplementor
  ): TimestampsRegion = delegate.buildTimestampsRegion(regionName, sessionFactory)

  override def buildQueryResultsRegion(
      regionName: String,
      sessionFactory: SessionFactoryImplementor
  ): QueryResultsRegion =
    val region = delegate.buildQueryResultsRegion(regionName, sessionFactory)
    new QueryResultsRegion:
      override def getName: String = region.getName
      override def getRegionFactory: RegionFactory = LocaleAwareRegionFactory.this
      override def clear(): Unit = region.clear()
      override def destroy(): Unit = region.destroy()

      private def scoped(key: Object, session: SharedSessionContractImplementor): Object =
        val tenant = session.getTenantIdentifierValue
        if tenant != null && !tenant.isInstanceOf[Serializable] then
          throw new HibernateException("A serializable tenant identifier is required for localized caching")
        LocaleQueryKey(SessionContentLocale.required(session), tenant, key)

      override def getFromCache(key: Object, session: SharedSessionContractImplementor): Object =
        region.getFromCache(scoped(key, session), session)

      override def putIntoCache(key: Object, value: Object, session: SharedSessionContractImplementor): Unit =
        region.putIntoCache(scoped(key, session), value, session)

