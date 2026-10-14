package com.music.vivi.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import com.music.vivi.R
import com.music.vivi.constants.*
import com.music.vivi.playback.PlayerConnection
import com.music.vivi.playback.automix.*
import com.music.vivi.utils.rememberPreference
import com.music.vivi.utils.makeTimeString
import kotlin.math.abs

/** Slider time scales are linear, including the wavy paths (their Y, not X, changes).
 * Insets match the pinned widgets: Material3 1.5 alpha27 half-4dp thumb,
 * ExpressiveLab v1.0.0 8dp thumb radius, zero for Wavy/Squiggly/SLIM. */
fun timelineMarkerX(fraction: Float, width: Float, inset: Float, reverse: Boolean): Float {
    val edge=inset.coerceIn(0f,width.coerceAtLeast(0f)/2)
    val f=if(reverse) 1-fraction.coerceIn(0f,1f) else fraction.coerceIn(0f,1f)
    return edge+(width-2*edge).coerceAtLeast(0f)*f
}

@Composable
fun AutoMixTimeline(connection: PlayerConnection, currentId: String?, durationMs: Long, positionMs: Long,
                    style: SliderStyle, trackPadding: Dp=0.dp, scrubbing: Boolean=false,
                    content: @Composable () -> Unit) {
    val show by rememberPreference(ShowAutoMixTransitionKey,false)
    if(!show) { content(); return }
    val state by connection.service.autoMixStatus.collectAsStateWithLifecycle()
    // Observe playlist changes without polling or scheduling any analysis in Compose.
    val queue by connection.queueWindows.collectAsStateWithLifecycle()
    val nextId=queue.getOrNull(connection.player.nextMediaItemIndex)?.mediaItem?.mediaId
    val fraction=if(scrubbing) null else state.markerFraction(currentId,nextId,durationMs)
    val description=stringResource(R.string.automix_marker_description, makeTimeString(state.plan?.outgoingStartMs ?: 0L))
    val color=MaterialTheme.colorScheme.tertiary
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl && style in setOf(SliderStyle.DEFAULT,SliderStyle.SLIM)
    Box(Modifier.fillMaxWidth()) {
        content()
        if(fraction!=null) Canvas(Modifier.matchParentSize().padding(horizontal=trackPadding)
            .semantics { contentDescription=description }) {
            val edge=when(style) { SliderStyle.DEFAULT -> 2.dp; SliderStyle.EXPRESSIVE -> 8.dp; else -> 0.dp }.toPx()
            val x=timelineMarkerX(fraction,size.width,edge,rtl)
            val thumb=timelineMarkerX((positionMs.toDouble()/durationMs).toFloat(),size.width,edge,rtl)
            // Keep clear of the thumb; this Canvas has no pointer input and cannot consume seeks.
            if(abs(x-thumb)>12.dp.toPx()) {
                val y=(center.y-17.dp.toPx()).coerceAtLeast(3.dp.toPx())
                drawLine(color,Offset(x,y),Offset(x,y+6.dp.toPx()),2.dp.toPx())
                drawCircle(color,2.dp.toPx(),Offset(x,y))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerDiagnostics(connection: PlayerConnection, modifier: Modifier=Modifier,
                      color: Color=MaterialTheme.colorScheme.onSurfaceVariant, remotePlayback: Boolean=false) {
    val show by rememberPreference(StatsForNerdsKey,false)
    if(!show) return
    val audio by connection.service.audioDiagnostics.collectAsStateWithLifecycle()
    val state by connection.service.autoMixStatus.collectAsStateWithLifecycle()
    val enabled by rememberPreference(AutoMixEnabledKey,false)
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val gate=remember { MixLogExportGate() }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    val mode by com.music.vivi.utils.rememberEnumPreference(AutoMixPerformanceKey,AutoMixPerformance.BALANCED)
    fun finish(success: Boolean) {
        gate.finish()
        android.widget.Toast.makeText(context,context.getString(if(success) { if(android.os.Build.VERSION.SDK_INT>=29) R.string.automix_log_saved else R.string.automix_log_saved_document } else R.string.automix_log_failed),android.widget.Toast.LENGTH_SHORT).show()
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report=pendingReport;pendingReport=null
        if(uri==null || report==null) gate.finish() else scope.launch {
            try {
                withContext(Dispatchers.IO) { MixLogWriter.save(SelectedMixLogDestination(context.contentResolver,uri),
                    "",report) { ensureActive() } }
                finish(true)
            } catch(error: Exception) { gate.finish();if(error !is CancellationException) finish(false) }
        }
    }
    fun export() {
        if(!gate.begin()) return
        scope.launch {
            try {
                val report=withContext(Dispatchers.IO) {
                    val installed=com.music.vivi.vivimusic.updater.installedVersion(context)
                    AutoMixRecorder.render(connection.service.autoMixRecorder.snapshot(),MixReportHeader(installed.name,installed.code,
                        android.os.Build.VERSION.RELEASE,android.os.Build.MODEL,enabled,mode))
                }
                val filename=MixLogWriter.filename()
                if(android.os.Build.VERSION.SDK_INT>=29) {
                    withContext(Dispatchers.IO) { MixLogWriter.save(DownloadsMixLogDestination(context.contentResolver),filename,report) { ensureActive() } }
                    finish(true)
                } else { pendingReport=report;picker.launch(filename) }
            } catch(error: Exception) { gate.finish();if(error !is CancellationException) finish(false) }
        }
    }
    val exportLabel=stringResource(R.string.automix_log_export)
    Column(modifier.fillMaxWidth().padding(vertical=2.dp)
        .combinedClickable(onClick={},onLongClickLabel=exportLabel,onLongClick=::export)
        .semantics { customActions=listOf(CustomAccessibilityAction(exportLabel) { export();true }) }) {
        val source=if(remotePlayback) stringResource(R.string.audio_info_remote) else audio.fields().joinToString(" · ")
            .ifBlank { stringResource(R.string.audio_info_unknown) }
        Text(stringResource(R.string.audio_info_source,source), color=color,
            style=MaterialTheme.typography.labelSmall)
        if(!remotePlayback && audio.outputSampleRate!=null) {
            val encoding=when(audio.outputEncoding) {
                C.ENCODING_PCM_16BIT -> "16-bit PCM"
                C.ENCODING_PCM_FLOAT -> "Float PCM"
                C.ENCODING_PCM_24BIT -> "24-bit PCM"
                C.ENCODING_PCM_32BIT -> "32-bit PCM"
                else -> stringResource(R.string.audio_encoding_unknown)
            }
            Text(stringResource(R.string.audio_info_app_output,encoding,audio.outputSampleRate!!/1000.0),
                color=color,style=MaterialTheme.typography.labelSmall)
        }
        if(enabled) Text(autoMixDescription(state),color=color,style=MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun autoMixDescription(state: AutoMixStatus): String {
    val phase=stringResource(when(state.phase) {
        AutoMixPhase.DISABLED -> R.string.automix_status_disabled
        AutoMixPhase.WAITING_NEXT -> R.string.automix_status_waiting_next
        AutoMixPhase.WAITING_SOURCE -> R.string.automix_status_waiting_source
        AutoMixPhase.ANALYZING_CURRENT -> R.string.automix_status_analyzing_current
        AutoMixPhase.ANALYZING_NEXT -> R.string.automix_status_analyzing_next
        AutoMixPhase.REFINING -> R.string.automix_status_refining
        AutoMixPhase.PLANNED -> R.string.automix_status_planned
        AutoMixPhase.PREPARING -> R.string.automix_status_preparing
        AutoMixPhase.READY -> R.string.automix_status_ready
        AutoMixPhase.MIXING -> R.string.automix_status_mixing
        AutoMixPhase.FALLBACK_PLANNED -> R.string.automix_status_fallback_planned
        AutoMixPhase.FALLBACK_PLAYING -> R.string.automix_status_fallback_playing
        AutoMixPhase.COMPLETED -> R.string.automix_status_completed
        AutoMixPhase.RECOVERING -> R.string.automix_status_recovering
        AutoMixPhase.UNAVAILABLE -> R.string.automix_status_unavailable
    })
    val plan=state.plan
    val technique=plan?.let { stringResource(when(it.technique) {
        MixTechnique.BEAT_BLEND -> R.string.automix_technique_beat
        MixTechnique.BASS_HANDOFF -> R.string.automix_technique_bass
        MixTechnique.GENTLE_FILTER -> R.string.automix_technique_filter
        MixTechnique.ENERGY_FADE -> R.string.automix_technique_energy
        MixTechnique.SAFE_FADE -> R.string.automix_technique_fallback
    }) }
    val base=if(plan!=null) stringResource(R.string.automix_plan_summary,phase,technique ?: "",
        makeTimeString(plan.outgoingStartMs),makeTimeString(plan.incomingCueMs),plan.durationMs/1000.0)
        else stringResource(R.string.automix_status_summary,phase)
    val summary=if(plan!=null) "$base · ${stringResource(when(plan.tier) {
        AutoMixTier.A -> R.string.automix_tier_a
        AutoMixTier.B -> R.string.automix_tier_b
        AutoMixTier.C -> R.string.automix_tier_c
    })}" + (state.actualStartMs?.let { " · ${stringResource(R.string.automix_actual_start,makeTimeString(it))}" } ?: "") else base
    if(state.reason=="coarse-analysis-recovery") return "$summary · ${stringResource(R.string.automix_recovered)}"
    if(state.reason==null || (plan!=null && plan.tier!=AutoMixTier.C)) return summary
    val reason=stringResource(when {
        state.reason.contains("source-not-range") -> R.string.automix_reason_seek
        state.reason.contains("source-unresolved") -> R.string.automix_reason_source
        state.reason.contains("deadline") || state.reason.contains("timeout") || state.reason.contains("canceled") -> R.string.automix_reason_deadline
        state.reason.contains("deck") -> R.string.automix_reason_deck
        state.reason.contains("missed") -> R.string.automix_reason_missed
        else -> R.string.automix_reason_analysis
    })
    return "$summary · $reason (${AutoMixFailure.from(state.reason).name})"
}
