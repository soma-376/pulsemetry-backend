package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.activePoint
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.boundary
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.dimension
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupDimensions
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.groupLimit
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.intervals
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.people
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.person
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.quoted
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.remaining
import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.tokenTypes
import com.team376.pulsemetry.persistence.telemetry.DashboardReadException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

// 본문은 원본 들여쓰기를 그대로 둔다 — SQL 리터럴이 executed_sql 로 응답에 실리므로 공백 한 칸도 바꾸지 않는다.
/** 비용 리더 — 약정 소진 · 비용 이상 · 비용 계열. `discounts` 는 계약 할인을 ClickHouse 리터럴로 만든다. */
    private fun DashboardQueryReaders.discounts(scope: Scope): String {
        val zone = jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant")
            .param("tenant",UUID.fromString(scope.parameters.getValue("tenant"))).query(String::class.java).single()
        val rows = jdbc.sql("""SELECT DISTINCT c.id,d.id AS discount_id,cm.member_id,c.vendor::text,d.model_pattern,d.discount_rate,
            ceil(extract(epoch FROM greatest(cm.assigned_at,c.starts_at::timestamp AT TIME ZONE :zone,
                d.effective_from::timestamp AT TIME ZONE :zone)))::bigint AS valid_from,
            ceil(extract(epoch FROM least(coalesce(cm.released_at,'infinity'::timestamptz),
                coalesce(c.terminated_at,'infinity'::timestamptz),
                coalesce(c.ends_at+1,DATE '9999-12-31')::timestamp AT TIME ZONE :zone,
                coalesce(d.effective_to+1,DATE '9999-12-31')::timestamp AT TIME ZONE :zone)))::bigint AS valid_to
            FROM enrollment.contracts c JOIN enrollment.contract_token_discounts d ON d.contract_id=c.id
            JOIN enrollment.contract_memberships cm ON cm.contract_id=c.id
            JOIN enrollment.members m ON m.id=cm.member_id AND m.tenant_id=c.tenant_id
            WHERE c.tenant_id=:tenant AND c.contract_type='token_discount' AND c.status!='draft' AND d.token_type='all'
            LIMIT 5001""").param("tenant",UUID.fromString(scope.parameters.getValue("tenant"))).param("zone",zone)
            .query().listOfRows()
        if (rows.size>5000) throw DashboardReadException("query_too_wide",422)
        return rows.joinToString(",","[","]") { row ->
            "(${quoted(row["member_id"].toString())},${quoted(row["vendor"].toString())},${row["valid_from"]},${row["valid_to"]},"+
                "${quoted(row["model_pattern"]?.toString() ?: "")},${row["discount_rate"]},${quoted(row["id"].toString())},${quoted(row["discount_id"].toString())})"
        }
    }

    /** 약정 계약의 기간·배정·벤더를 모두 만족하는 관측만 비용과 보호 인원에 포함한다. */
    internal fun DashboardQueryReaders.readCommitment(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long): Rows {
        val tenant = UUID.fromString(scope.parameters.getValue("tenant"))
        val contractZone = jdbc.sql("SELECT timezone FROM enrollment.tenants WHERE id=:tenant")
            .param("tenant",tenant).query(String::class.java).single()
        val selected = q.params["contract_id"]?.asString()
        val records = jdbc.sql("""SELECT c.id,c.vendor::text,tc.commitment_amount,tc.currency,cm.member_id,
            ceil(extract(epoch FROM greatest(cm.assigned_at,c.starts_at::timestamp AT TIME ZONE :zone)))::bigint AS valid_from,
            ceil(extract(epoch FROM least(coalesce(cm.released_at,'infinity'::timestamptz),
                coalesce(c.terminated_at,'infinity'::timestamptz),
                coalesce(c.ends_at+1,DATE '9999-12-31')::timestamp AT TIME ZONE :zone)))::bigint AS valid_to
            FROM enrollment.contracts c JOIN enrollment.contract_term_commitments tc ON tc.contract_id=c.id
            JOIN enrollment.contract_memberships cm ON cm.contract_id=c.id
            JOIN enrollment.members m ON m.id=cm.member_id AND m.tenant_id=c.tenant_id
            WHERE c.tenant_id=:tenant AND c.contract_type='term_commitment' AND c.status!='draft'
                AND (:all OR c.id=:contract) ORDER BY c.id,cm.member_id LIMIT 5001""")
            .param("tenant",tenant).param("zone",contractZone).param("all",selected==null)
            .param("contract",selected?.let(UUID::fromString) ?: UUID(0,0)).query().listOfRows()
        val contracts = records.groupBy { it["id"].toString() }
        if (records.size>5000 || contracts.size>groupLimit(q)) throw DashboardReadException("query_too_wide",422)
        val statements = mutableListOf<String>()
        val result = contracts.flatMap { (id, members) ->
            val info = members.first()
            if (info["currency"].toString().trim()!="USD") throw DashboardReadException("unsupported_contract_currency",422)
            val amount = (info["commitment_amount"] as? java.math.BigDecimal)?.toDouble()
            if (amount!=null && (!amount.isFinite() || amount<0)) throw DashboardReadException("invalid_commitment_amount",422)
            val membership = members.joinToString(",","[","]") {
                "(${quoted(it["member_id"].toString())},${it["valid_from"]},${it["valid_to"]},${quoted(it["vendor"].toString())})"
            }
            val costs = readCost(q.copy(metricId="cost",priceBasis="contract"),scope,from,to,zone,interval,deadline,membership)
            statements += costs.sql
            costs.data.map { row ->
                val node = row.deepCopy() as tools.jackson.databind.node.ObjectNode
                node.put("g0",id)
                node.set("numerator",row["value"])
                if (amount==null) node.putNull("denominator") else node.put("denominator",amount)
                if (amount==null || amount==0.0 || row["value"].isNull) node.putNull("value")
                else node.put("value",row["value"].asDouble()/amount)
                node
            }
        }
        return Rows(result,statements.distinct().joinToString("\n"))
    }

    /** 누락일은 0원으로 추정하지 않는다. 기준일 각각의 개인정보 보호 조건도 결과에 전파한다. */
    internal fun DashboardQueryReaders.readAnomaly(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, deadline: Long, retained: List<List<String>>? = null): Rows {
        val days = (q.params["moving_avg_days"] ?: q.params["window_days"])?.asInt() ?: 7
        val timezone = ZoneId.of(zone)
        val start = from.atZone(timezone).toLocalDate().atStartOfDay(timezone)
        val daily = readCost(q.copy(metricId="cost",frameType="timeseries",
            source=if ("agent_name" in q.groupBy) "metrics" else "events"),scope,
            start.minusDays(days.toLong()).toInstant(),to,zone,"1d",deadline,retained=retained)
        val groups = daily.data.groupBy { row -> q.groupBy.indices.map { row["g$it"].asString() } }
        val result = groups.values.flatMap { rows ->
            val byDay = rows.associateBy { Instant.ofEpochMilli(it["bucket"].asLong()).atZone(timezone).toLocalDate() }
            val selected = rows.filter { it["bucket"].asLong()>=start.toInstant().toEpochMilli() }
            val output = if (q.frameType=="timeseries") selected else selected.takeLast(1)
            output.map { row ->
                val date = Instant.ofEpochMilli(row["bucket"].asLong()).atZone(timezone).toLocalDate()
                val prior = (1..days).map { byDay[date.minusDays(it.toLong())] }
                val node = row.deepCopy() as tools.jackson.databind.node.ObjectNode
                val observed = listOf(row)+prior.filterNotNull()
                node.put("people",observed.minOf { it["people"].asLong() })
                node.put("active_time_definition",if (observed.any { it["active_time_definition"].asInt()>0 }) 1 else 0)
                node.put("cumulative",observed.sumOf { it["cumulative"].asLong() })
                val baseline = if (prior.any { it==null || it["value"].isNull }) null else prior.sumOf { it!!["value"].asDouble() }/days
                node.set("numerator",row["value"])
                if (baseline==null) node.putNull("denominator") else node.put("denominator",baseline)
                if (baseline==null || baseline==0.0 || row["value"].isNull) node.putNull("value")
                else node.put("value",row["value"].asDouble()/baseline-1)
                node
            }
        }
        return Rows(result,daily.sql)
    }

    internal fun DashboardQueryReaders.readCost(q: DashboardQueryItem, scope: Scope, from: Instant, to: Instant,
        zone: String, interval: String, deadline: Long, membership: String? = null, retained: List<List<String>>? = null): Rows {
        val unitPrice = q.metricId=="model_unit_price"
        val perUser = q.metricId=="cost_per_active_user"
        val perHour = q.metricId=="cost_per_user_hour"
        val subagent = q.metricId=="subagent_cost_ratio"
        val metrics = q.source=="metrics" || subagent
        val contract = q.priceBasis=="contract"
        val frameType = q.frameType ?: requireNotNull(catalog.find(q.metricId)).defaultFrameType
        val bucket = if (frameType=="timeseries") "toUnixTimestamp(toStartOfInterval(ts, INTERVAL ${intervals.getValue(interval)}, {zone:String}))*1000" else "0"
        val expressions = q.groupBy.map { dim ->
            when (dim) {
                "query_source" -> if (metrics) "JSONExtractString(raw_json,'point','attrs','query_source')" else "JSONExtractString(raw_json,'payload','source')"
                "agent_name","skill_name","plugin_name" -> "JSONExtractString(raw_json,'point','attrs','${dim.substringBefore('_')}.name')"
                "mcp_server" -> if (metrics) "JSONExtractString(raw_json,'point','attrs','mcp_server.name')" else dimension(dim)
                "speed" -> "JSONExtractString(raw_json,'point','attrs','speed')"
                "effort" -> if (metrics) "JSONExtractString(raw_json,'point','attrs','effort')" else "JSONExtractString(raw_json,'payload','reasoning_effort')"
                else -> dimension(dim)
            }
        }
        val dimensions = groupDimensions(expressions,retained)
        val groups = (listOf("bucket")+q.groupBy.indices.map { "g$it" }).joinToString(",")
        val source = if (metrics) "signal='metric' AND product='claude_code' AND JSONExtractString(raw_json,'point','name')='claude_code.cost.usage'"
            else "signal IN ('log','span') AND JSONExtractString(raw_json,'type')='llm_call'"
        val observed = if (metrics) "$source AND JSONExtractInt(raw_json,'point','aggregation_temporality')!=2" else source
        val value = if (metrics) "JSONExtract(raw_json,'point','value','Nullable(Float64)')" else "JSONExtract(raw_json,'payload','cost_usd','Nullable(Float64)')"
        val tokenValues = tokenTypes.map { "JSONExtract(raw_json,'payload','tokens','$it','Nullable(Float64)')" }
        val valid = "$observed AND isNotNull(raw_cost) AND raw_cost>=0"+
            if (unitPrice) " AND "+tokenValues.joinToString(" AND ") { "isNotNull($it) AND $it>=0" } else ""
        val matched = "arrayFilter(d -> d.1=$person AND d.2=multiIf(product='claude_code','anthropic',product='codex','openai','') AND toInt64(toUnixTimestamp(ts))>=d.3 AND toInt64(toUnixTimestamp(ts))<d.4 AND position(${dimension("model")},d.5)>0,{discounts:Array(Tuple(String,String,Int64,Int64,String,Float64,String,String))})"
        val membershipFilter = if (membership==null) "" else " AND arrayExists(cm -> cm.1=$person AND toInt64(toUnixTimestamp(ts))>=cm.2 AND toInt64(toUnixTimestamp(ts))<cm.3 AND cm.4=multiIf(product='claude_code','anthropic',product='codex','openai',''),{commitments:Array(Tuple(String,Int64,Int64,String))})"
        val input = "SELECT *,$value AS raw_cost,${if (contract) matched else "[]"} AS matched $base$membershipFilter"
        val factor = if (contract) "if(empty(matched),1.0,matched[1].6)" else "1.0"
        val activity = "JSONExtract(raw_json,'point','value','Nullable(Float64)')"
        val denominator = if (unitPrice) "sumOrNullIf(${tokenValues.joinToString("+")},$valid)" else if (perUser) "people" else "sumOrNullIf($activity,$activePoint AND $activity>=0)/3600.0"
        val aggregate = if (perUser || perHour || unitPrice) """sumOrNullIf(raw_cost*$factor,$valid) AS numerator,
            $denominator AS denominator,if(denominator=0,NULL,numerator/denominator) AS value""" else if (subagent) """sumOrNullIf(raw_cost*$factor,$valid) AS denominator,
            if(countIf($valid)=0,NULL,sumIf(raw_cost*$factor,$valid AND JSONExtractString(raw_json,'point','attrs','query_source')='subagent')) AS numerator,
            if(denominator=0,NULL,numerator/denominator) AS value""" else "sumOrNullIf(raw_cost*$factor,$valid) AS value"
        val sql = """SELECT $bucket AS bucket${if (dimensions.isEmpty()) "" else ","+dimensions.joinToString(",")},
            $people AS people,countIf($activePoint)>0 AS active_time_definition,
            countIf($observed) AS points,
            ${if (metrics) "countIf($source AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2)" else if (perUser || perHour) "countIf(signal='metric' AND product='claude_code' AND JSONExtractString(raw_json,'point','name')='claude_code.active_time.total' AND JSONExtractString(raw_json,'point','attrs','type')='user' AND JSONExtractInt(raw_json,'point','aggregation_temporality')=2)" else "0"} AS cumulative,
            $aggregate,
            countIf($valid AND length(matched)>1) AS conflicts,
            countIf($valid AND (NOT isFinite($factor) OR $factor<0)) AS invalid_rates,
            countIf($valid AND JSONExtractString(raw_json,'payload','cost_source')='reported') AS reported,
            countIf($valid AND JSONExtractString(raw_json,'payload','cost_source')='estimated') AS estimated
            FROM ($input)
            ${if ("team" in q.groupBy) "ARRAY JOIN arrayFilter(t -> {unrestricted:UInt8}=1 OR has({teams:Array(String)},t),team_ids_as_of) AS team" else ""}
            GROUP BY $groups ORDER BY $groups"""
        val parameters = scope.parameters+mapOf("from" to boundary(from),"to" to boundary(to),"zone" to zone,
            "discounts" to if (contract) discounts(scope) else "[]", "commitments" to (membership ?: "[]"),
            "retained" to mapper.writeValueAsString(retained.orEmpty()).replace("\\", "\\\\"))
        val rows = mapper.readTree(reader.query(sql,parameters,remaining(deadline)))["data"].toList()
            .filter { it["points"].asLong()>0 || it["cumulative"].asLong()>0 }
        if (rows.any { it["conflicts"].asLong()>0 }) throw DashboardReadException("contract_overlap",422)
        if (rows.any { it["invalid_rates"].asLong()>0 }) throw DashboardReadException("invalid_contract_rate",422)
        return Rows(rows,sql)
    }
