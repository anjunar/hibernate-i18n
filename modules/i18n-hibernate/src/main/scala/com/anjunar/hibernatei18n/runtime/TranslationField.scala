package com.anjunar.hibernatei18n.runtime

final class TranslationField[P, V] private (
  val name: String,
  val read: P => V,
  val encode: V => Object,
  val decode: Object => V
):
  private[runtime] def readAny(page: P): Any = read(page)
  private[runtime] def encodeAny(value: Any): Object =
    encode(value.asInstanceOf[V])

object TranslationField:
  private[runtime] def mapped[P](
    name: String,
    read: P => AnyRef,
    toDatabase: AnyRef => Object,
    fromDatabase: Object => AnyRef
  ): TranslationField[P, AnyRef] =
    new TranslationField(
      name,
      read,
      value => if value == null then null else toDatabase(value),
      value => if value == null then null else fromDatabase(value)
    )

  def string[P](name: String, read: P => String): TranslationField[P, String] =
    new TranslationField[P, String](name, read, identity, _.asInstanceOf[String])

  def converted[P, V](
    name: String,
    read: P => V,
    toDatabase: V => String,
    fromDatabase: String => V
  ): TranslationField[P, V] =
    new TranslationField[P, V](
      name,
      read,
      value => if value == null then null else toDatabase(value),
      value =>
        if value == null then null.asInstanceOf[V]
        else fromDatabase(value.asInstanceOf[String])
    )
