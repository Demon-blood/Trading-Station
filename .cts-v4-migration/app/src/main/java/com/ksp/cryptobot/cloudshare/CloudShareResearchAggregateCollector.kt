package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CloudShareResearchAggregateCollector(private val cloudDao: CloudShareDao, private val researchDao: ResearchDao) {
    private val adapter=Moshi.Builder().build().adapter<Map<String,Any?>>(Types.newParameterizedType(Map::class.java,String::class.java,Any::class.java))
    suspend fun collectRecent(days:Int=7):Int{
        val since=System.currentTimeMillis()-days.coerceAtLeast(1)*86_400_000L
        val rows=researchDao.recentEvents(20_000).filter{it.timestampEpochMs>=since}; var queued=0
        fun day(ms:Long)=DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
        val research=rows.filter{it.eventType=="research_evaluation"}.groupBy{listOf(day(it.timestampEpochMs),it.strategy,it.regime,it.variant.ifBlank{"none"})}
        for((k,v)in research)queued+=queue("shared_research_daily",v.maxOf{it.timestampEpochMs},mapOf("day" to k[0],"strategy" to k[1],"regime" to k[2],"variant" to k[3],"sample_count" to v.size,"avg_score" to v.map{it.score}.average()))
        val variants=rows.filter{it.eventType=="strategy_variant"}.groupBy{listOf(day(it.timestampEpochMs),it.symbol,it.variant.ifBlank{"none"})}
        for((k,v)in variants)queued+=queue("shared_strategy_variant_daily",v.maxOf{it.timestampEpochMs},mapOf("day" to k[0],"symbol" to k[1],"variant" to k[2],"sample_count" to v.size,"avg_adjustment" to v.map{it.adjustment}.average()))
        val wf=rows.filter{it.eventType=="walk_forward"}.groupBy{listOf(day(it.timestampEpochMs),it.strategy,it.trainWindow,it.testWindow)}
        for((k,v)in wf)queued+=queue("shared_walk_forward_daily",v.maxOf{it.timestampEpochMs},mapOf("day" to k[0],"strategy" to k[1],"train_window" to k[2],"test_window" to k[3],"sample_count" to v.size,"avg_score" to v.map{it.score}.average()))
        val onchain=rows.filter{it.eventType=="onchain_context"}.groupBy{listOf(day(it.timestampEpochMs),it.symbol,it.provider.lowercase(),it.status.uppercase())}
        for((k,v)in onchain)queued+=queue("shared_onchain_daily",v.maxOf{it.timestampEpochMs},mapOf("day" to k[0],"symbol" to k[1],"provider" to k[2],"status" to k[3],"sample_count" to v.size,"avg_score" to v.map{it.adjustment}.average()))
        return queued
    }
    private suspend fun queue(source:String,ts:Long,payload:Map<String,Any?>):Int{
        val e=CloudShareEvent.create(source,Instant.ofEpochMilli(ts).toString(),payload)
        val row=CloudShareOutboxEntity(eventId=e.eventId,sourceTable=e.sourceTable,eventTimestamp=e.eventTimestamp,schemaVersion=e.schemaVersion,payloadJson=adapter.toJson(e.payload),payloadSha256=e.payloadSha256)
        return if(cloudDao.enqueue(row)==-1L)0 else 1
    }
}
