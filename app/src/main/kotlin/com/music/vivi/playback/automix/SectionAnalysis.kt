package com.music.vivi.playback.automix

import kotlin.math.*

/** Measured energy/vocal-risk changes, not semantic chorus/phrase recognition. */
fun detectSectionBoundaries(energy: List<EnergyPoint>, beatConfidence: Double): List<SectionBoundary> {
    val proposals = energy.mapNotNull { point ->
        val before=energy.filter { it.positionMs in point.positionMs-5000..point.positionMs-1000 }
        val after=energy.filter { it.positionMs in point.positionMs+1000..point.positionMs+5000 }
        if(before.size<6 || after.size<6) return@mapNotNull null
        val a=before.map { it.rms }.average(); val b=after.map { it.rms }.average()
        val va=before.map { it.vocalRisk }.average(); val vb=after.map { it.vocalRisk }.average()
        val energyChange=abs(a-b)/max(a,b).coerceAtLeast(0.005)
        val confidence=(max(energyChange,abs(va-vb))*0.9+beatConfidence*0.15).coerceIn(0.0,1.0)
        if(confidence<0.6) return@mapNotNull null
        // Pin a broad comparison plateau to an observed adjacent energy/vocal change.
        val local=energy.zipWithNext().filter { abs(it.second.positionMs-point.positionMs)<=2000 }
        val change=local.maxByOrNull { abs(it.second.rms-it.first.rms)/max(it.second.rms,it.first.rms).coerceAtLeast(0.005)+abs(it.second.vocalRisk-it.first.vocalRisk) }
        SectionBoundary(change?.second?.positionMs ?: point.positionMs,confidence,a,b,va,vb)
    }.sortedByDescending { it.confidence }
    val selected=mutableListOf<SectionBoundary>()
    proposals.forEach { p -> if(selected.none { abs(it.positionMs-p.positionMs)<2500 }) selected+=p }
    return selected.sortedBy { it.positionMs }
}

/** Retain usable local windows, including tempo changes; one failed pass must not erase another. */
fun mergeTrackFeatures(parts: List<TrackFeatures>, preferTail: Boolean): TrackFeatures? {
    if(parts.isEmpty()) return null
    val sorted=parts.sortedBy { it.startMs }
    val anchor=if(preferTail) sorted.last() else sorted.first()
    val compatible=sorted.filter { abs(it.durationMs-anchor.durationMs)<=1000 }
    val total=compatible.sumOf { it.lengthMs }.coerceAtLeast(1)
    val energy=compatible.sortedBy { if(it.sections.any { s -> s.detailed }) 1 else 0 }.flatMap { it.energy }.groupBy { it.positionMs }.map { (_,points) -> points.last() }.sortedBy { it.positionMs }
    return anchor.copy(startMs=compatible.minOf { it.startMs },lengthMs=compatible.maxOf { it.startMs+it.lengthMs }-compatible.minOf { it.startMs },
        rms=sqrt(compatible.sumOf { it.rms*it.rms*it.lengthMs }/total),peak=compatible.maxOf { it.peak },
        beatsMs=compatible.flatMap { it.beatsMs }.distinct().sorted(),energy=energy,
        key=compatible.lastOrNull { it.sections.any { s -> s.detailed } }?.key ?: anchor.key,
        sections=compatible.flatMap { it.sections },boundaries=detectSectionBoundaries(energy,compatible.maxOf { it.beatConfidence }))
}
