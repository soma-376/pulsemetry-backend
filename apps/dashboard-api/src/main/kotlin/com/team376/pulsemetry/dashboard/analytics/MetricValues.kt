package com.team376.pulsemetry.dashboard.analytics

 data class Value<T>(val value: T?, val status: String, val reasons: List<String>)
 data class Metric<T>(val value: T?, val status: String, val reasons: List<String>, val comparison: Change<T>)
 data class Change<T>(val status: String, val reasons: List<String>, val previousValue: T?, val absoluteChange: T?,
  val percentChange: Double?, val change: String?)
