/*
 * This file is part of HyperCeiler.
 * Licensed under the GNU Affero General Public License, version 3 or later.
 */
package com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui

import android.telephony.SubscriptionManager
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.getStateFlowValue
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.newReadonlyStateFlow
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.setStateFlowValue
import java.util.function.Consumer

/**
 * Relays the activity Flows supplied by MiuiMobileIconVMImpl's outer getters.
 * The outer ViewModel fields are never overwritten; the native Binder gets a
 * stable Flow only for the retained slot-0 view.
 */
internal class ActiveDataSimActivityRelay(private val defaultValue: Any, private val name: String) {
    private class Source(val flow: Any, var job: KotlinJob? = null)

    private val lock = Any()
    private val sources = HashMap<Int, Source>()
    private val lastValues = HashMap<Int, Any>()
    private var proxy: Any? = null

    fun attach(subId: Int, slot: Int, originalFlow: Any): Any? = synchronized(lock) {
        if (slot !in 0..1) return@synchronized null

        if (sources[subId]?.flow !== originalFlow) {
            val previous = sources.remove(subId)
            runCatching { previous?.job?.cancel() }
            lastValues.remove(subId)

            val source = Source(originalFlow)
            sources[subId] = source
            runCatching { getStateFlowValue(originalFlow) }.getOrNull()
                ?.let { lastValues[subId] = it }

            val collector = runCatching {
                MiuiStub.javaAdapter.alwaysCollectFlow(originalFlow, Consumer<Any> { value ->
                    synchronized(lock) {
                        if (sources[subId] === source) {
                            lastValues[subId] = value
                            val selected = subId == SubscriptionManager.getDefaultDataSubscriptionId()
                            Sim2ActivityTrace.log("outer.$name source selected=$selected", slot, originalFlow, value)
                            if (selected) {
                                proxy?.let {
                                    setStateFlowValue(it, value)
                                    Sim2ActivityTrace.log("outer.$name forwarded", slot, it, value)
                                }
                            }
                        }
                    }
                })
            }.getOrNull()

            if (collector == null) {
                sources.remove(subId)
                lastValues.remove(subId)
                return@synchronized null
            }
            source.job = collector
            Sim2ActivityTrace.log("outer.$name sourceAttached", slot, originalFlow, lastValues[subId])
        }

        if (slot != 0) return@synchronized null

        proxy?.let { return@synchronized it }
        val activeSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val initialValue = lastValues[activeSubId]
            ?: sources[activeSubId]?.flow?.let {
                runCatching { getStateFlowValue(it) }.getOrNull()
            }
            ?: if (activeSubId == subId) lastValues[subId] ?: defaultValue else defaultValue
        newReadonlyStateFlow(initialValue).also { proxy = it }
    }

    fun syncActive() = synchronized(lock) {
        val activeSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val value = lastValues[activeSubId]
            ?: sources[activeSubId]?.flow?.let {
                runCatching { getStateFlowValue(it) }.getOrNull()
            }
            ?: defaultValue
        proxy?.let {
            setStateFlowValue(it, value)
            Sim2ActivityTrace.log("outer.$name switched", SubscriptionManager.getSlotIndex(activeSubId), it, value)
        }
    }
}
