package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OverviewFailureTest {
 private val org=Organization(UUID.randomUUID(),"org","Asia/Seoul")
 private val user=UUID.randomUUID()
 private val day=LocalDate.parse("2026-09-07")
 private val period=ComparedPeriod(DatePeriod(day,day,QueryReader.SEOUL),CompareMode.NONE)
 private val snapshot=mock(SnapshotManifestStore.Manifest::class.java)
 private val frame=AnalyticsFrames.Frame(org,period,snapshot,IngestStatusReader.History(null),setOf(day),setOf(day),Coverage("complete",1),null,"ready",false,Instant.parse("2026-09-08T00:00:00Z"))
 private val frames=mock(AnalyticsFrames::class.java)
 private val aggregate=mock(UsageAggregator::class.java)
 private val refs=mock(SnapshotReferences::class.java)
 private val service=OverviewService(frames,aggregate,refs)
 init {
  `when`(snapshot.pricingVersions).thenReturn(emptyList())
  `when`(frames.frame(org,user,period,true,null)).thenReturn(frame)
  `when`(frames.meta(frame)).thenReturn(AnalyticsPeriodMeta(org.id.toString(),frame.now.toString(),null,"USD",day.toString(),day.toString(),"Asia/Seoul",1,"ready",frame.currentCoverage,null))
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.ORGANIZATION)).thenReturn(mapOf(emptyList<String?>() to UsageTotals.EMPTY))
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.DAY)).thenReturn(emptyMap())
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.MODEL)).thenReturn(emptyMap())
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.TEAM)).thenReturn(emptyMap())
  `when`(refs.products(snapshot)).thenReturn(emptyList())
  `when`(refs.teams(snapshot)).thenReturn(emptyList())
 }
 @Test fun `one section failure preserves other sections and next single request recovers`() {
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.DAY)).thenThrow(StoreUnavailableException("private diagnostic")).thenReturn(emptyMap())
  val partial=service.overview(org,user,period)
  assertThat(partial.summary).isNotNull();assertThat(partial.modelMix).isNotNull();assertThat(partial.teamUsage).isNotNull();assertThat(partial.trend).isNull()
  assertThat(partial.errors).containsExactly(OverviewResponse.SectionError("trend","source_unavailable",true))
  val recovered=service.overview(org,user,period)
  assertThat(recovered.errors).isEmpty();assertThat(recovered.trend!!.points).hasSize(1)
  verify(frames,times(2)).frame(org,user,period,true,null)
  verify(aggregate,times(2)).totals(snapshot,Side.CURRENT,Axis.ORGANIZATION)
 }
 @Test fun `multiple query failures keep section identity and safe public codes`() {
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.ORGANIZATION)).thenThrow(StoreQueryRejectedException("secret SQL"))
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.MODEL)).thenThrow(StoreUnavailableException("secret host"))
  val result=service.overview(org,user,period)
  assertThat(result.summary).isNull();assertThat(result.modelMix).isNull();assertThat(result.trend).isNotNull()
  assertThat(result.errors).containsExactly(OverviewResponse.SectionError("summary","query_failed",false),OverviewResponse.SectionError("modelMix","source_unavailable",true))
 }
 @Test fun `RDS team reference failure does not hide summary or charts`() {
  `when`(refs.products(snapshot)).thenThrow(DataAccessResourceFailureException("private connection"))
  val result=service.overview(org,user,period)
  assertThat(result.teamUsage).isNull();assertThat(result.summary).isNotNull();assertThat(result.trend).isNotNull();assertThat(result.modelMix).isNotNull()
  assertThat(result.errors).containsExactly(OverviewResponse.SectionError("teamUsage","source_unavailable",true))
 }
 @Test fun `empty period does not query sections or manufacture query errors`() {
  val empty=frame.copy(dataState="no_data",currentCoverage=Coverage("none",0))
  `when`(frames.frame(org,user,period,true,null)).thenReturn(empty)
  `when`(frames.meta(empty)).thenReturn(AnalyticsPeriodMeta(org.id.toString(),frame.now.toString(),null,"USD",day.toString(),day.toString(),"Asia/Seoul",1,"no_data",empty.currentCoverage,null))
  val result=service.overview(org,user,period)
  assertThat(result.summary).isNull();assertThat(result.trend).isNull();assertThat(result.modelMix).isNull();assertThat(result.teamUsage).isNull();assertThat(result.errors).isEmpty()
  verifyNoInteractions(aggregate,refs)
 }
 @Test fun `common snapshot failure and unexpected programming errors are not disguised as partial data`() {
  `when`(frames.frame(org,user,period,true,null)).thenThrow(StoreUnavailableException("snapshot"))
  assertThatThrownBy {service.overview(org,user,period)}.isInstanceOf(StoreUnavailableException::class.java)
  verifyNoInteractions(aggregate)
  doReturn(frame).`when`(frames).frame(org,user,period,true,null)
  `when`(aggregate.totals(snapshot,Side.CURRENT,Axis.DAY)).thenThrow(IllegalStateException("bug"))
  assertThatThrownBy {service.overview(org,user,period)}.isInstanceOf(IllegalStateException::class.java)
 }
}
