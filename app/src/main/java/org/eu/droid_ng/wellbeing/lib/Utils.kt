package org.eu.droid_ng.wellbeing.lib

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log
import java.time.*
import java.util.*

object Utils {
    private const val mostUsedPackagesCacheSize: Int = 3
    private const val mostUsedPackagesMinUsageInSeconds: Long = 5
    private var calculatedUsageStats: HashMap<String, Duration>? = null
    private var calculatedScreenTime: Duration? = null
    private var mostUsedPackages: Array<String>? = null
    const val PACKAGE_MANAGER_MATCH_INSTANT = 0x00800000
    @JvmField
    val blackListedPackages: HashSet<String> = HashSet()
    @JvmField
    val restrictedPackages: HashSet<String> = HashSet()

    private fun eventsStr(events: Iterable<UsageEvents.Event>): String {
        val b = StringBuilder()
        b.append("[")
        for (element in events) {
            b.append("el(t=").append(element.eventType).append("), ")
        }
        b.replace(b.length - 2, b.length - 1, "]")
        return b.toString()
    }

    @JvmStatic
    fun clearUsageStatsCache(usm: UsageStatsManager?, pm: PackageManager?, recalculate: Boolean) {
        calculatedUsageStats = null
        calculatedScreenTime = null
        mostUsedPackages = null
        if (recalculate) {
            updateApplicationBlackLists(pm!!)
            checkInitializeCache(usm!!)
        }
    }

    @JvmStatic
    fun getTimeUsed(usm: UsageStatsManager, packageName: String?): Duration {
        checkInitializeCache(usm)
        return calculatedUsageStats!!.getOrDefault(packageName, Duration.ZERO)
    }

    @JvmStatic
    fun getTimeUsed(usm: UsageStatsManager, packageNames: Array<String?>): Duration {
        checkInitializeCache(usm)
        var d = Duration.ZERO
        for (packageName in packageNames) {
            d = d.plus(calculatedUsageStats!!.getOrDefault(packageName, Duration.ZERO))
        }
        return if (d.isNegative) Duration.ZERO else d
    }

    @JvmStatic
    fun getScreenTime(usm: UsageStatsManager): Duration {
        checkInitializeCache(usm)
        return calculatedScreenTime!!
    }

    @JvmStatic
    fun getMostUsedPackages(usm: UsageStatsManager): Array<String> {
        checkInitializeCache(usm)
        return mostUsedPackages!!
    }

    /*
     * When writing this code, I learnt a lesson. UsageStats and UsageEvents APIs are fucking dumb.
     * I had cases of user opening the app 3 times and closing it 2 times, cases of user opening the app 2 times without closing it at all...
     * But in the very end this works. And it's about 3 trillion times faster than UsageStatsManager queries.
     */
    private fun checkInitializeCache(usm: UsageStatsManager) {
        if (calculatedUsageStats != null) return
        // Cache not available. Calculate it once and keep it.
        val z = ZoneId.systemDefault()
        val startTime = LocalDateTime.of(LocalDate.now(z), LocalTime.MIDNIGHT).atZone(z)
                .toEpochSecond() * 1000
        val usageEvents: UsageEvents = usm.queryEvents(startTime, System.currentTimeMillis())
        var currentEvent: UsageEvents.Event
        val e = HashMap<String, ArrayList<UsageEvents.Event>>()
        while (usageEvents.hasNextEvent()) {
            currentEvent = UsageEvents.Event()
            usageEvents.getNextEvent(currentEvent)
            if (currentEvent.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    currentEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                e.computeIfAbsent(
                        currentEvent.packageName
                ) { ArrayList() }
                        .add(currentEvent)
            }
        }
        // Calculate usageStats
        calculatedUsageStats = HashMap<String, Duration>()
        e.forEach { (pkgName: String, events: ArrayList<UsageEvents.Event>) ->
            var i = 0
            while (i < events.size) {
                var j = 1
                if (i + j >= events.size) {
                    i += 2
                    continue
                }
                val eventOne = events[i]
                var eventTwo = events[i + j]
                if (eventOne.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Log.e("AppTimersInternal", "usm soft assert1 fail!! eventOneType=" + eventOne.eventType + " eventTwoType=" + eventTwo.eventType)
                    i += 1
                    continue
                    // Unlucky case. Skip one. Should never happen, but safe is safe. edit: happens. help me
                }
                if (eventOne.eventType != UsageEvents.Event.ACTIVITY_RESUMED) {
                    Log.e("AppTimersInternal", "usm soft assert2 fail!! pkgName=$pkgName i=$i j=$j events=${eventsStr(events)}")
                    i += 2
                    continue
                    // did not find start
                }
                while (events[i + j].also { eventTwo = it }.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    j++
                    if (i + j == events.size) {
                        Log.e("AppTimersInternal", "usm soft assert3 fail!! pkgName=$pkgName i=$i j=$j events=${eventsStr(events)}")
                        break
                        // did not find ending
                    }
                }
                if (eventTwo.eventType != UsageEvents.Event.ACTIVITY_PAUSED) {
                    Log.e("AppTimersInternal", "usm soft assert4 fail!! pkgName=$pkgName i=$i j=$j events=${eventsStr(events)}")
                    i += 2
                    continue
                    // did not find ending
                }
                calculatedUsageStats!![pkgName] = calculatedUsageStats!!.getOrDefault(pkgName, Duration.ZERO)
                        .plus(Duration.ofMillis(eventTwo.timeStamp - eventOne.timeStamp))
                i += 2
            }
        }
        // Calculate screenTime + mostUsedPackages
        var screenTimeTmp: Duration = Duration.ZERO
        val mostUsedPackagesTmp = arrayOfNulls<String>(mostUsedPackagesCacheSize)
        val mostUsedPackageTime = Array(mostUsedPackagesCacheSize) { mostUsedPackagesMinUsageInSeconds }
        calculatedUsageStats!!.forEach { (pkgName: String, duration: Duration) ->
            val seconds: Long
            if (!blackListedPackages.contains(pkgName)) {
                screenTimeTmp = screenTimeTmp.plus(duration)
                seconds = duration.seconds
            } else seconds = 0
            if (seconds > mostUsedPackageTime[mostUsedPackagesCacheSize - 1]) {
                var index = 0
                while (seconds <= mostUsedPackageTime[index]) {
                    index++
                }
                System.arraycopy(mostUsedPackagesTmp, index,
                        mostUsedPackagesTmp, index + 1,
                        (mostUsedPackagesCacheSize - 1) - index)
                System.arraycopy(mostUsedPackageTime, index,
                        mostUsedPackageTime, index + 1,
                        (mostUsedPackagesCacheSize - 1) - index)
                mostUsedPackagesTmp[index] = pkgName
                mostUsedPackageTime[index] = seconds
            }
        }
        calculatedScreenTime = screenTimeTmp
        if (mostUsedPackagesTmp[mostUsedPackagesCacheSize - 1] != null) {
            @Suppress("UNCHECKED_CAST")
            mostUsedPackages = mostUsedPackagesTmp as Array<String>
        } else if (mostUsedPackagesTmp[0] == null) {
            mostUsedPackages = emptyArray()
        } else {
            var arraySize = mostUsedPackagesCacheSize
            while (arraySize --> 0) {
                if (mostUsedPackagesTmp[arraySize] != null) {
                    arraySize + 1
                    break
                }
            }
            @Suppress("UNCHECKED_CAST")
            mostUsedPackages = mostUsedPackagesTmp.copyOf(arraySize) as Array<String>
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun updateApplicationBlackLists(pm: PackageManager) {
        blackListedPackages.clear()
        restrictedPackages.clear()

        blackListedPackages.add("com.android.systemui")
        val resId = Resources.getSystem().getIdentifier(
                "config_recentsComponentName", "string", "android")
        if (resId != 0) {
            val recentsComponent = ComponentName.unflattenFromString(
                    Resources.getSystem().getString(resId))
            if (recentsComponent != null)
                blackListedPackages.add(recentsComponent.packageName)
        }
        var intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        addDefaultHandlersToBlacklist(pm, intent, blackListedPackages)
        blackListedPackages.remove("com.android.settings")
        restrictedPackages.addAll(blackListedPackages)
        restrictedPackages.add("com.android.settings")
        // Add every system dialer to the blacklist
        intent = Intent(Intent.ACTION_DIAL)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        addDefaultHandlersToBlacklist(pm, intent, restrictedPackages)
        restrictedPackages.add("org.eu.droid_ng.wellbeing")
        Log.d("Utils", "Hard Blacklisted packages: $blackListedPackages")
        Log.d("Utils", "Soft Blacklisted packages: $restrictedPackages")
    }

    private fun addDefaultHandlersToBlacklist(pm: PackageManager, intent: Intent, blacklist: HashSet<String>) {
        // Add the system handlers to the blacklist
        val resolveInfoList = pm.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
        if (resolveInfoList.isNotEmpty()) {
            for (resolveInfo in resolveInfoList) {
                blacklist.add(resolveInfo.activityInfo.packageName)
            }
        }
        // Add the default handler to the blacklist
        val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo != null) {
            blacklist.add(resolveInfo.activityInfo.packageName)
        }
    }
}