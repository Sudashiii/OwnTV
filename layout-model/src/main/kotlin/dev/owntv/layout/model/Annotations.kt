package dev.owntv.layout.model

import kotlinx.serialization.SerialInfo

/** Description for people and AI. Feeds the JSON Schema and, later, the on-TV editor. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Doc(val value: String)

/** Marks a string (or list of strings) as a color: `#RRGGBB`, `#AARRGGBB` or a theme token such as `@primary`. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ColorValue

/** Inclusive numeric range, checked by the validator and written to the schema. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Range(val min: Double, val max: Double)
